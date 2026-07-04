package ai.careerpilot.workflow.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 3A — pipeline ENTRY event for the application-tracking workflow. Minted by the dark-by-default
 * {@code WorkflowEntryBridge} (off a human seed action or 2E's ApplicationSubmittedEvent), which is
 * where the {@code correlationId} originates. Consumer: {@code ApplicationTrackingWorker}.
 */
public record ApplicationCreatedEvent(UUID eventId, UUID correlationId, UUID userId, UUID jobId,
                                      UUID applicationId, Instant timestamp,
                                      String company, String country, String source)
        implements BaseWorkflowEvent {

    public static ApplicationCreatedEvent open(UUID correlationId, UUID userId, UUID jobId,
                                               UUID applicationId, String company, String country, String source) {
        return new ApplicationCreatedEvent(UUID.randomUUID(), correlationId, userId, jobId, applicationId,
                Instant.now(), company, country, source);
    }
}
