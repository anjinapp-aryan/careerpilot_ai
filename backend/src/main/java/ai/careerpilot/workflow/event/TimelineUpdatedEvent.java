package ai.careerpilot.workflow.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 3A.2 — emitted after a timeline entry is appended. Consumer: {@code EmailIntelligenceWorker}.
 */
public record TimelineUpdatedEvent(UUID eventId, UUID correlationId, UUID userId, UUID jobId,
                                   UUID applicationId, Instant timestamp, String eventType)
        implements BaseWorkflowEvent {

    public static TimelineUpdatedEvent from(BaseWorkflowEvent prev, String eventType) {
        return new TimelineUpdatedEvent(UUID.randomUUID(), prev.correlationId(), prev.userId(),
                prev.jobId(), prev.applicationId(), Instant.now(), eventType);
    }
}
