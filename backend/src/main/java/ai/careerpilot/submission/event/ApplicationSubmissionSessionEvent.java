package ai.careerpilot.submission.event;

import java.util.UUID;

/**
 * Phase 7.16 — published on every {@code ApplicationSubmissionSession} status transition,
 * in-process via {@link org.springframework.context.ApplicationEventPublisher} — NOT Kafka,
 * mirroring {@code workflow.tracking}'s own convention. Purely informational today (no listener
 * ships in this phase); reserved for future Copilot/notification hooks.
 */
public record ApplicationSubmissionSessionEvent(UUID sessionId, UUID userId, UUID jobId, String newStatus) {
}
