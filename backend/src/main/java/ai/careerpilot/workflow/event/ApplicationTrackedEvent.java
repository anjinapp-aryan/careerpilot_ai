package ai.careerpilot.workflow.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 3A.1 — emitted after the application lifecycle row is created/updated. Consumer:
 * {@code StatusDetectionWorker}.
 */
public record ApplicationTrackedEvent(UUID eventId, UUID correlationId, UUID userId, UUID jobId,
                                      UUID applicationId, Instant timestamp, String status)
        implements BaseWorkflowEvent {

    public static ApplicationTrackedEvent from(BaseWorkflowEvent prev, String status) {
        return new ApplicationTrackedEvent(UUID.randomUUID(), prev.correlationId(), prev.userId(),
                prev.jobId(), prev.applicationId(), Instant.now(), status);
    }
}
