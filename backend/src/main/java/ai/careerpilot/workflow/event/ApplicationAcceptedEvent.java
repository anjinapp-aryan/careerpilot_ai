package ai.careerpilot.workflow.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 3A.5 — candidate accepted an offer (final positive terminal signal). Named in the Phase 3A.0
 * examples as a canonical {@link BaseWorkflowEvent} child. Consumer: {@code AnalyticsWorker}.
 */
public record ApplicationAcceptedEvent(UUID eventId, UUID correlationId, UUID userId, UUID jobId,
                                       UUID applicationId, Instant timestamp)
        implements BaseWorkflowEvent {

    public static ApplicationAcceptedEvent from(BaseWorkflowEvent prev) {
        return new ApplicationAcceptedEvent(UUID.randomUUID(), prev.correlationId(), prev.userId(),
                prev.jobId(), prev.applicationId(), Instant.now());
    }
}
