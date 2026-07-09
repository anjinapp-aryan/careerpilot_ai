package ai.careerpilot.workflow.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 3A.4 — emitted by {@code InterviewDetectionWorker} when an interview signal is found.
 * Named in the Phase 3A.0 examples as a canonical {@link BaseWorkflowEvent} child. Consumer:
 * {@code InterviewTrackingWorker}.
 */
public record InterviewDetectedEvent(UUID eventId, UUID correlationId, UUID userId, UUID jobId,
                                     UUID applicationId, Instant timestamp, String interviewType)
        implements BaseWorkflowEvent {

    public static InterviewDetectedEvent from(BaseWorkflowEvent prev, String interviewType) {
        return new InterviewDetectedEvent(UUID.randomUUID(), prev.correlationId(), prev.userId(),
                prev.jobId(), prev.applicationId(), Instant.now(), interviewType);
    }
}
