package ai.careerpilot.service;

import ai.careerpilot.ai.AiGatewayService;
import ai.careerpilot.ai.ChatMessage;
import ai.careerpilot.api.dto.CopilotDtos.CopilotStreamRequest;
import ai.careerpilot.domain.CopilotConversation;
import ai.careerpilot.domain.CopilotMessage;
import ai.careerpilot.security.AuthenticatedUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Orchestrates a single streaming Copilot turn end-to-end:
 * resolve conversation → persist the user turn → assemble a grounded, page-aware
 * prompt (via {@link AgentOrchestrator}) → stream the model response → persist
 * the assistant turn on completion. Returns the live token {@link Flux} for the
 * SSE controller to relay to the browser.
 */
@Service
public class CopilotService {

    private static final Logger log = LoggerFactory.getLogger(CopilotService.class);

    private final AiGatewayService ai;
    private final ConversationMemory memory;
    private final AgentOrchestrator orchestrator;

    public CopilotService(AiGatewayService ai, ConversationMemory memory, AgentOrchestrator orchestrator) {
        this.ai = ai;
        this.memory = memory;
        this.orchestrator = orchestrator;
    }

    /** The live result of a turn: the (possibly newly created) conversation id + token stream. */
    public record StreamResult(UUID conversationId, Flux<String> tokens) {}

    public StreamResult streamTurn(AuthenticatedUser user, CopilotStreamRequest req) {
        CopilotConversation conv = memory.resolve(user.userId(), user.orgId(), req.conversationId(), req.page());

        // The user's message: their typed text, or the canned message behind a quick-action.
        String userMessage = (req.message() != null && !req.message().isBlank())
                ? req.message().strip()
                : orchestrator.defaultMessage(req.action());
        memory.append(conv.getId(), "USER", userMessage, req.action());

        // System prompt = persona + task; grounding context goes in the first model-visible turn.
        String system = orchestrator.systemPrompt(req.page(), req.action());
        String context = orchestrator.contextBlock(user, req.page(), req.action(), req.contextId());

        List<ChatMessage> turns = buildTurns(conv.getId(), context);

        StringBuilder assistant = new StringBuilder();
        // Single entry point for AI: Gemini → DeepSeek → Qwen with automatic,
        // user-invisible failover. The Copilot never talks to a provider directly.
        Flux<String> tokens = ai.streamChat(turns, system)
                .doOnNext(assistant::append)
                .doOnComplete(() -> persistAssistantAsync(conv.getId(), assistant.toString()))
                .onErrorResume(err -> {
                    log.warn("Copilot stream failed for conversation {}: {}", conv.getId(), err.toString());
                    String msg = "\n\n_The assistant hit an error generating a response. Please try again._";
                    persistAssistantAsync(conv.getId(), assistant + msg);
                    return Flux.just(assistant.length() == 0 ? msg.strip() : msg);
                });

        return new StreamResult(conv.getId(), tokens);
    }

    /**
     * Builds the model-visible turn list: a leading grounded user/model pair carrying the
     * CONTEXT, followed by the real conversation history (USER/ASSISTANT only).
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
    private void persistAssistantAsync(UUID conversationId, String content) {
        if (content == null || content.isBlank()) return;
        Schedulers.boundedElastic().schedule(() -> {
            try {
                memory.append(conversationId, "ASSISTANT", content, null);
            } catch (Exception e) {
                log.error("Failed to persist assistant message for {}", conversationId, e);
            }
        });
    }
}
