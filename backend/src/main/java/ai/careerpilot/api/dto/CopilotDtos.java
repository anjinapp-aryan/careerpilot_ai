package ai.careerpilot.api.dto;

import ai.careerpilot.domain.CopilotConversation;
import ai.careerpilot.domain.CopilotMessage;

import java.time.Instant;
import java.util.UUID;

/** Request/response shapes for the AI Copilot surface. */
public final class CopilotDtos {

    private CopilotDtos() {}

    /**
     * A streaming chat turn.
     *
     * @param conversationId existing conversation to continue, or null to start a new one
     * @param page           current app surface: resume | jobs | applications | workflow | dashboard
     * @param action         optional quick-action key (improve_resume, ats_analysis, …)
     * @param message        the user's free-text message (optional when an action is supplied)
     * @param contextId      optional entity id for grounding (resume/job/application UUID, or a
     *                       workflow thread id) — the orchestrator falls back to the most recent
     *                       relevant entity when absent
     */
    public record CopilotStreamRequest(
            UUID conversationId,
            String page,
            String action,
            String message,
            String contextId) {}

    public record ConversationSummary(
            UUID id, String page, String title, Instant createdAt, Instant updatedAt) {
        public static ConversationSummary from(CopilotConversation c) {
            return new ConversationSummary(c.getId(), c.getPage(), c.getTitle(), c.getCreatedAt(), c.getUpdatedAt());
        }
    }

    public record MessageView(
            UUID id, String role, String content, String action, Instant createdAt) {
        public static MessageView from(CopilotMessage m) {
            return new MessageView(m.getId(), m.getRole(), m.getContent(), m.getAction(), m.getCreatedAt());
        }
    }
}
