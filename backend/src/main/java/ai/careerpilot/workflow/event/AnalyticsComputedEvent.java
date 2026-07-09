package ai.careerpilot.workflow.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 3A.5 → 3A.6 — emitted after analytics rollups are recomputed. Consumer:
 * {@code CareerIntelligenceWorker} (terminal — publishes nothing further).
 */
public record AnalyticsComputedEvent(UUID eventId, UUID correlationId, UUID userId, UUID jobId,
                                     UUID applicationId, Instant timestamp)
        implements BaseWorkflowEvent {

    public static AnalyticsComputedEvent from(BaseWorkflowEvent prev) {
        return new AnalyticsComputedEvent(UUID.randomUUID(), prev.correlationId(), prev.userId(),
                prev.jobId(), prev.applicationId(), Instant.now());
    }
}
