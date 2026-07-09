package ai.careerpilot.workflow.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 3A.1 — emitted by {@code StatusDetectionWorker} once the next lifecycle status is derived.
 * Consumer: {@code TimelineWorker}.
 */
public record StatusDetectedEvent(UUID eventId, UUID correlationId, UUID userId, UUID jobId,
                                  UUID applicationId, Instant timestamp,
                                  String detectedStatus, String previousStatus)
        implements BaseWorkflowEvent {

    public static StatusDetectedEvent from(BaseWorkflowEvent prev, String detected, String previous) {
        return new StatusDetectedEvent(UUID.randomUUID(), prev.correlationId(), prev.userId(),
                prev.jobId(), prev.applicationId(), Instant.now(), detected, previous);
    }
}
