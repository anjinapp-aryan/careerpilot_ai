package ai.careerpilot.workflow.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 3A.5 — negative terminal signal. Named in the Phase 3A.0 examples as a canonical
 * {@link BaseWorkflowEvent} child. Consumer: {@code AnalyticsWorker}.
 */
public record ApplicationRejectedEvent(UUID eventId, UUID correlationId, UUID userId, UUID jobId,
                                       UUID applicationId, Instant timestamp, String reason)
        implements BaseWorkflowEvent {

    public static ApplicationRejectedEvent from(BaseWorkflowEvent prev, String reason) {
        return new ApplicationRejectedEvent(UUID.randomUUID(), prev.correlationId(), prev.userId(),
                prev.jobId(), prev.applicationId(), Instant.now(), reason);
    }
}
