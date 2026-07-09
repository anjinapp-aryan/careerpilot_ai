package ai.careerpilot.learning.event;

import ai.careerpilot.learning.LearningEventType;

import java.util.UUID;

/** Phase 6.1 — published after a {@code LearningEvent} row is persisted; drives the pattern-learning stages. */
public record LearningEventRecordedEvent(
        UUID learningEventId,
        UUID correlationId,
        UUID userId,
        UUID jobId,
        LearningEventType eventType) {
}
