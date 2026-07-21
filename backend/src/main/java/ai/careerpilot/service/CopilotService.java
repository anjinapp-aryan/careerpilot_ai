package ai.careerpilot.service;

import ai.careerpilot.ai.AiGatewayService;
import ai.careerpilot.ai.ChatMessage;
import ai.careerpilot.api.dto.CopilotDtos.CopilotStreamRequest;
import ai.careerpilot.domain.CopilotConversation;
import ai.careerpilot.domain.CopilotMessage;
import ai.careerpilot.security.AuthenticatedUser;
import ai.careerpilot.service.copilot.CopilotSkillHandler;
import ai.careerpilot.service.copilot.CopilotSkillRouter;
import ai.careerpilot.service.copilot.SkillContext;
import ai.careerpilot.service.copilot.event.CopilotUserMessageEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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

    public CopilotService(AiGatewayService ai, ConversationMemory memory,
                          CopilotSkillRouter skillRouter, AgentOrchestrator orchestrator,
                          CareerContextRetriever contextRetriever,
                          ApplicationEventPublisher events,
                          @org.springframework.beans.factory.annotation.Value(
                                  "${copilot.candidate-profile-context.enabled:false}") boolean candidateProfileContextEnabled,
                          @org.springframework.beans.factory.annotation.Value(
                                  "${copilot.career-memory-context.enabled:false}") boolean careerMemoryContextEnabled,
                          @org.springframework.beans.factory.annotation.Value(
                                  "${copilot.career-memory-context.limit:5}") int careerMemoryContextLimit) {
        this.ai = ai;
        this.memory = memory;
        this.skillRouter = skillRouter;
        this.orchestrator = orchestrator;
        this.contextRetriever = contextRetriever;
        this.events = events;
        this.candidateProfileContextEnabled = candidateProfileContextEnabled;
        this.careerMemoryContextEnabled = careerMemoryContextEnabled;
        this.careerMemoryContextLimit = careerMemoryContextLimit;
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

        StringBuilder assistant = new StringBuilder();
        Flux<String> tokens = ai.streamChat(turns, system, providerCallback)
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
