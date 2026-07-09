package ai.careerpilot.workflow.event;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3A.0 — every 3A event implements {@link BaseWorkflowEvent} and each stage's {@code from(prev,
 * ...)} factory MUST carry the correlation id (and the user/job/application identity) forward unchanged.
 * This is what stitches the nine independently-published events into one traceable workflow instance.
 */
class BaseWorkflowEventTest {

    private final UUID correlationId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();
    private final UUID applicationId = UUID.randomUUID();

    @Test
    void entryEventCarriesMintedCorrelation() {
        ApplicationCreatedEvent e = ApplicationCreatedEvent.open(
                correlationId, userId, jobId, applicationId, "Acme", "US", "seed");
        assertThat(e.correlationId()).isEqualTo(correlationId);
        assertThat(e.userId()).isEqualTo(userId);
        assertThat(e.jobId()).isEqualTo(jobId);
        assertThat(e.applicationId()).isEqualTo(applicationId);
        assertThat(e.eventId()).isNotNull();
        assertThat(e.timestamp()).isNotNull();
    }

    @Test
    void correlationPropagatesAcrossTheWholeChain() {
        ApplicationCreatedEvent created = ApplicationCreatedEvent.open(
                correlationId, userId, jobId, applicationId, "Acme", "US", "seed");
        ApplicationTrackedEvent tracked = ApplicationTrackedEvent.from(created, "SUBMITTED");
        StatusDetectedEvent detected = StatusDetectedEvent.from(tracked, "VIEWED", "SUBMITTED");
        TimelineUpdatedEvent timeline = TimelineUpdatedEvent.from(detected, "VIEWED");
        EmailProcessedEvent email = EmailProcessedEvent.from(timeline, "UNKNOWN", 0.0);
        InterviewDetectedEvent idet = InterviewDetectedEvent.from(email, "TECHNICAL");
        InterviewTrackedEvent itrk = InterviewTrackedEvent.from(idet, UUID.randomUUID(), "SCHEDULED");
        OfferReceivedEvent offer = OfferReceivedEvent.from(itrk, null);
        AnalyticsComputedEvent analytics = AnalyticsComputedEvent.from(offer);

        for (BaseWorkflowEvent e : new BaseWorkflowEvent[]{
                created, tracked, detected, timeline, email, idet, itrk, offer, analytics}) {
            assertThat(e.correlationId()).as(e.getClass().getSimpleName() + " correlationId").isEqualTo(correlationId);
            assertThat(e.userId()).as(e.getClass().getSimpleName() + " userId").isEqualTo(userId);
            assertThat(e.jobId()).as(e.getClass().getSimpleName() + " jobId").isEqualTo(jobId);
            assertThat(e.eventId()).as(e.getClass().getSimpleName() + " eventId").isNotNull();
        }
    }

    @Test
    void eachEventMintsItsOwnEventId() {
        ApplicationCreatedEvent created = ApplicationCreatedEvent.open(
                correlationId, userId, jobId, applicationId, "Acme", "US", "seed");
        ApplicationTrackedEvent tracked = ApplicationTrackedEvent.from(created, "SUBMITTED");
        assertThat(tracked.eventId()).isNotEqualTo(created.eventId());
    }

    @Test
    void terminalBranchEventsAlsoPropagateCorrelation() {
        ApplicationCreatedEvent created = ApplicationCreatedEvent.open(
                correlationId, userId, jobId, applicationId, "Acme", "US", "seed");
        ApplicationRejectedEvent rejected = ApplicationRejectedEvent.from(created, "lifecycle rejected");
        ApplicationAcceptedEvent accepted = ApplicationAcceptedEvent.from(created);
        assertThat(rejected.correlationId()).isEqualTo(correlationId);
        assertThat(accepted.correlationId()).isEqualTo(correlationId);
    }
}
