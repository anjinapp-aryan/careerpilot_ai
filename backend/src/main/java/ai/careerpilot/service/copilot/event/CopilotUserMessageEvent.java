package ai.careerpilot.service.copilot.event;

import java.util.UUID;

/**
 * Phase 7.15.2 — published once per USER turn (never for assistant replies) by
 * {@code CopilotService.streamTurn}, after {@code ConversationMemory.append} has already
 * committed that message. The sole consumer today is
 * {@code ai.careerpilot.memory.conversation.ConversationIntelligenceWorker} — same "purely
 * additive listener" shape as {@code LearningEventBridge}/{@code CareerMemoryEventBridge}. No
 * existing Copilot code path is modified to consume this; it only observes.
 */
public record CopilotUserMessageEvent(UUID conversationId, UUID userId, String message, String page, String action) {
}
