package ai.careerpilot.workflow.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 3A.3 — emitted after the (inert, deterministic) email intelligence pass classifies an
 * application email. Consumer: {@code InterviewDetectionWorker}.
 */
public record EmailProcessedEvent(UUID eventId, UUID correlationId, UUID userId, UUID jobId,
                                  UUID applicationId, Instant timestamp,
                                  String category, double confidence)
        implements BaseWorkflowEvent {

    public static EmailProcessedEvent from(BaseWorkflowEvent prev, String category, double confidence) {
        return new EmailProcessedEvent(UUID.randomUUID(), prev.correlationId(), prev.userId(),
                prev.jobId(), prev.applicationId(), Instant.now(), category, confidence);
    }
}
