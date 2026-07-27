package ai.careerpilot.service;

import ai.careerpilot.ai.AiGatewayService;
import ai.careerpilot.ai.ChatMessage;
import ai.careerpilot.api.dto.CopilotDtos.CopilotStreamRequest;
import ai.careerpilot.capability.CapabilityAwareChatService;
import ai.careerpilot.capability.CapabilityDecision;
import ai.careerpilot.capability.CapabilityEngine;
import ai.careerpilot.capability.CapabilityMetrics;
import ai.careerpilot.capability.CapabilityResult;
import ai.careerpilot.domain.CopilotConversation;
import ai.careerpilot.domain.CopilotMessage;
import ai.careerpilot.mcp.McpExecutionContext;
import ai.careerpilot.security.AuthenticatedUser;
import ai.careerpilot.service.copilot.CopilotSkillHandler;
import ai.careerpilot.service.copilot.CopilotSkillRouter;
import ai.careerpilot.service.copilot.SkillContext;
import ai.careerpilot.service.copilot.event.CopilotUserMessageEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Enterprise Career Intelligence Assistant orchestration.
 * Routes requests to specialized skill handlers, assembles RAG context,
 * and streams responses with source attribution via the AI Gateway.
 *
 * Every skill also receives the candidate's canonical profile automatically here (not in any
 * individual handler) via {@code contextRetriever.getCandidateProfileContext}, gated by
 * {@code copilot.candidate-profile-context.enabled} — see {@link SkillContext#candidateProfileBlock()}.
 */
@Service
public class CopilotService {

    private static final Logger log = LoggerFactory.getLogger(CopilotService.class);

    private final AiGatewayService ai;
    private final ConversationMemory memory;
    private final CopilotSkillRouter skillRouter;
    private final AgentOrchestrator orchestrator;
    private final CareerContextRetriever contextRetriever;
    private final ApplicationEventPublisher events;
    private final boolean candidateProfileContextEnabled;
    private final boolean careerMemoryContextEnabled;
    private final int careerMemoryContextLimit;
    private final ObjectProvider<CapabilityEngine> capabilityEngineProvider;
    private final ObjectProvider<CapabilityAwareChatService> capabilityAwareChatServiceProvider;
    private final ObjectProvider<CapabilityMetrics> capabilityMetricsProvider;
    private final boolean capabilityIntegrationEnabled;

    public CopilotService(AiGatewayService ai, ConversationMemory memory,
                          CopilotSkillRouter skillRouter, AgentOrchestrator orchestrator,
                          CareerContextRetriever contextRetriever,
                          ApplicationEventPublisher events,
                          @org.springframework.beans.factory.annotation.Value(
                                  "${copilot.candidate-profile-context.enabled:false}") boolean candidateProfileContextEnabled,
                          @org.springframework.beans.factory.annotation.Value(
                                  "${copilot.career-memory-context.enabled:false}") boolean careerMemoryContextEnabled,
                          @org.springframework.beans.factory.annotation.Value(
                                  "${copilot.career-memory-context.limit:5}") int careerMemoryContextLimit,
                          ObjectProvider<CapabilityEngine> capabilityEngineProvider,
                          ObjectProvider<CapabilityAwareChatService> capabilityAwareChatServiceProvider,
                          ObjectProvider<CapabilityMetrics> capabilityMetricsProvider,
                          @org.springframework.beans.factory.annotation.Value(
                                  "${copilot.capability.integration.enabled:false}") boolean capabilityIntegrationEnabled) {
        this.ai = ai;
        this.memory = memory;
        this.skillRouter = skillRouter;
        this.orchestrator = orchestrator;
        this.contextRetriever = contextRetriever;
        this.events = events;
        this.candidateProfileContextEnabled = candidateProfileContextEnabled;
        this.careerMemoryContextEnabled = careerMemoryContextEnabled;
        this.careerMemoryContextLimit = careerMemoryContextLimit;
        this.capabilityEngineProvider = capabilityEngineProvider;
        this.capabilityAwareChatServiceProvider = capabilityAwareChatServiceProvider;
        this.capabilityMetricsProvider = capabilityMetricsProvider;
        this.capabilityIntegrationEnabled = capabilityIntegrationEnabled;
    }

    /** The live result of a turn: the conversation id + token stream + sources + provider reference. */
    public record StreamResult(UUID conversationId, Flux<String> tokens, List<String> sources, java.util.concurrent.atomic.AtomicReference<String> providerRef) {}

    public StreamResult streamTurn(AuthenticatedUser user, CopilotStreamRequest req) {
        CopilotConversation conv = memory.resolve(user.userId(), user.orgId(), req.conversationId(), req.page());

        // The user's message: their typed text, or the canned message behind a quick-action.
        String userMessage = (req.message() != null && !req.message().isBlank())
                ? req.message().strip()
                : orchestrator.defaultMessage(req.action());
        memory.append(conv.getId(), "USER", userMessage, req.action());

        // Phase 7.15.2 — purely additive: ConversationIntelligenceWorker is the only listener,
        // and it never blocks or affects this turn (async, its own executor, never throws).
        // Published unconditionally; the listener itself checks
        // career.memory.conversation.enabled, same "publisher never checks flags" convention as
        // LearningEventBridge/CareerMemoryEventBridge.
        events.publishEvent(new CopilotUserMessageEvent(conv.getId(), user.userId(), userMessage, req.page(), req.action()));

        log.info("Copilot turn started: action={}, page={}, contextId={}", req.action(), req.page(), req.contextId());

        // Route to appropriate skill handler
        CopilotSkillHandler handler = skillRouter.route(req.action(), userMessage);
        SkillContext skillCtx = new SkillContext(user, userMessage, req.contextId(), req.page());

        // Assemble RAG context
        try {
            handler.assembleContext(skillCtx);
        } catch (Exception e) {
            log.warn("Could not assemble full context for skill: {}", e.getMessage());
            // Continue with partial context — graceful degradation
        }

        // Every skill automatically gets the candidate's known profile (career goals,
        // preferences, excluded-roles memory) without any handler having to ask for it —
        // dark-shipped behind copilot.candidate-profile-context.enabled for instant rollback.
        if (candidateProfileContextEnabled) {
            try {
                skillCtx.candidateProfile(contextRetriever.getCandidateProfileContext(user));
            } catch (Exception e) {
                log.debug("No candidate profile context available: {}", e.getMessage());
            }
        }

        // Same centralized, no-handler-touches-it pattern for Career Decision Memory (Phase
        // 7.15.1) — ranked top-N only, never the full ledger. Separate flag from
        // career.memory.enabled (which gates writing) so extraction and Copilot exposure can be
        // canaried independently — a bad extraction is contained until this is also flipped on.
        if (careerMemoryContextEnabled) {
            try {
                skillCtx.memories(contextRetriever.getRelevantMemories(user, careerMemoryContextLimit));
            } catch (Exception e) {
                log.debug("No career memory context available: {}", e.getMessage());
            }
        }

        String system = handler.systemPrompt(skillCtx);
        String contextBlock = handler.contextBlock(skillCtx) + skillCtx.candidateProfileBlock() + skillCtx.memoriesBlock();
        String sourcesBlock = skillCtx.sourcesBlock();

        List<ChatMessage> turns = buildTurns(conv.getId(), contextBlock);

        // Track which provider was used via callback. Capture the callback in a variable
        // that can be shared through Reactor's async pipeline (ThreadLocal won't work across threads).
        java.util.concurrent.atomic.AtomicReference<String> usedProvider = new java.util.concurrent.atomic.AtomicReference<>("Unknown");
        java.util.function.Consumer<String> providerCallback = providerName -> {
            usedProvider.set(providerName);
            log.info("Copilot response from provider: {}", providerName);
        };

        Flux<String> rawTokens = routeTokens(turns, system, user, conv.getId(), userMessage, providerCallback, usedProvider);

        StringBuilder assistant = new StringBuilder();
        Flux<String> tokens = rawTokens
                .doOnNext(assistant::append)
                .doOnComplete(() -> {
                    persistAssistantAsync(conv.getId(), assistant.toString(), sourcesBlock);
                })
                .onErrorResume(err -> {
                    log.warn("Copilot stream failed for conversation {}: {}", conv.getId(), err.toString());
                    String msg = "\n\n_I'm temporarily unable to generate a response. Trying backup AI model..._";
                    persistAssistantAsync(conv.getId(), assistant + msg, sourcesBlock);
                    return Flux.just(assistant.length() == 0 ? msg.strip() : msg);
                });

        return new StreamResult(conv.getId(), tokens, new ArrayList<>(skillCtx.sources()), usedProvider);
    }

    /**
     * Phase 10.4 — decides, per turn, whether to route through {@link CapabilityAwareChatService}
     * (MCP tool calling) or the existing, byte-for-byte-unchanged {@code ai.streamChat(...)} call.
     * Dark-shipped behind {@code copilot.capability.integration.enabled} (default {@code false}) —
     * with it off, or with either {@link CapabilityEngine}/{@link CapabilityAwareChatService}
     * unavailable (their own {@code capability.engine.enabled} flag off), this returns exactly
     * the same {@code ai.streamChat(...)} call as before this phase.
     *
     * <p>Capability routing only replaces the streaming source when a capability actually
     * matched — general chat always keeps the real token-by-token stream from {@code
     * AiGatewayService.streamChat}, since {@link CapabilityAwareChatService#chat} is
     * synchronous and would otherwise collapse general-chat responses into a single chunk,
     * a user-visible regression the phase spec explicitly forbids ("user must NOT notice any
     * UI change"). Any exception during capability routing (decision, tool execution, or Spring
     * AI synthesis) falls back to the plain streaming call — never fails the request.
     */
    private Flux<String> routeTokens(List<ChatMessage> turns, String system, AuthenticatedUser user,
                                      UUID conversationId, String userMessage,
                                      Consumer<String> providerCallback,
                                      java.util.concurrent.atomic.AtomicReference<String> usedProvider) {
        if (capabilityIntegrationEnabled) {
            CapabilityEngine engine = capabilityEngineProvider.getIfAvailable();
            CapabilityAwareChatService capabilityService = capabilityAwareChatServiceProvider.getIfAvailable();
            if (engine != null && capabilityService != null) {
                long start = System.currentTimeMillis();
                try {
                    CapabilityDecision decision = engine.analyze(userMessage);
                    if (decision.useToolCalling()) {
                        McpExecutionContext mcpContext = new McpExecutionContext(
                                user.userId(), user.orgId(), conversationId.toString(),
                                UUID.randomUUID().toString(), Duration.ofSeconds(30), Map.of());
                        CapabilityResult result = capabilityService.chat(turns, system, mcpContext);
                        long elapsed = System.currentTimeMillis() - start;
                        CapabilityMetrics metrics = capabilityMetricsProvider.getIfAvailable();
                        if (metrics != null) {
                            metrics.recordEndToEndLatency(elapsed);
                        }
                        log.info("Copilot capability turn: type={} usedSpringAi={} latencyMs={}",
                                result.capabilityType(), result.usedSpringAi(), elapsed);
                        usedProvider.set(result.usedSpringAi()
                                ? "Spring AI Tool Calling (" + result.capabilityType() + ")"
                                : "AI Gateway (tool-augmented: " + result.capabilityType() + ")");
                        return Flux.just(result.answer());
                    }
                } catch (Exception e) {
                    log.warn("Capability routing failed for conversation {}, falling back to AI Gateway: {}",
                            conversationId, e.toString());
                }
            }
        }
        return ai.streamChat(turns, system, providerCallback);
    }

    /**
     * Builds the model-visible turn list: a leading grounded user/model pair carrying the
     * RAG context (sources + data), followed by the real conversation history (USER/ASSISTANT only).
     */
    private List<ChatMessage> buildTurns(UUID conversationId, String context) {
        List<ChatMessage> turns = new ArrayList<>();
        turns.add(ChatMessage.user("Here is the relevant context for this request:\n\n" + context));
        turns.add(ChatMessage.model("Understood. I'll use this context to help."));

        for (CopilotMessage m : memory.recentHistory(conversationId)) {
            if ("USER".equals(m.getRole())) {
                turns.add(ChatMessage.user(m.getContent()));
            } else if ("ASSISTANT".equals(m.getRole())) {
                turns.add(ChatMessage.model(m.getContent()));
            }
        }
        return turns;
    }

    /**
     * Persists the assistant turn off the reactive event-loop thread so the
     * blocking JDBC write never stalls token delivery.
     */
    private void persistAssistantAsync(UUID conversationId, String content, String sourcesBlock) {
        if (content == null || content.isBlank()) return;
        Schedulers.boundedElastic().schedule(() -> {
            try {
                String contentWithSources = content + (sourcesBlock != null ? sourcesBlock : "");
                memory.append(conversationId, "ASSISTANT", contentWithSources, null);
            } catch (Exception e) {
                log.error("Failed to persist assistant message for {}", conversationId, e);
            }
        });
    }
}
