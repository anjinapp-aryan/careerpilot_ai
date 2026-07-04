package ai.careerpilot.workflow.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 3A.4 — emitted after an interview row is persisted. Consumer: {@code OfferDetectionWorker}.
 */
public record InterviewTrackedEvent(UUID eventId, UUID correlationId, UUID userId, UUID jobId,
                                    UUID applicationId, Instant timestamp,
                                    UUID interviewId, String result)
        implements BaseWorkflowEvent {

    public static InterviewTrackedEvent from(BaseWorkflowEvent prev, UUID interviewId, String result) {
        return new InterviewTrackedEvent(UUID.randomUUID(), prev.correlationId(), prev.userId(),
                prev.jobId(), prev.applicationId(), Instant.now(), interviewId, result);
    }
}
