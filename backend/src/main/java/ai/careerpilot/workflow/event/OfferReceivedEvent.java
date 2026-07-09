package ai.careerpilot.workflow.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 3A.5 — emitted by {@code OfferDetectionWorker} on a positive terminal signal. Named in the
 * Phase 3A.0 examples as a canonical {@link BaseWorkflowEvent} child. Consumer: {@code AnalyticsWorker}.
 */
public record OfferReceivedEvent(UUID eventId, UUID correlationId, UUID userId, UUID jobId,
                                 UUID applicationId, Instant timestamp, String salary)
        implements BaseWorkflowEvent {

    public static OfferReceivedEvent from(BaseWorkflowEvent prev, String salary) {
        return new OfferReceivedEvent(UUID.randomUUID(), prev.correlationId(), prev.userId(),
                prev.jobId(), prev.applicationId(), Instant.now(), salary);
    }
}
