package ai.careerpilot.learning;

import ai.careerpilot.execution.event.ApplicationSubmittedEvent;
import ai.careerpilot.resumetailoring.event.RecommendationApprovedEvent;
import ai.careerpilot.resumetailoring.event.ResumeTailoredEvent;
import ai.careerpilot.workflow.event.AnalyticsComputedEvent;
import ai.careerpilot.workflow.event.ApplicationAcceptedEvent;
import ai.careerpilot.workflow.event.ApplicationRejectedEvent;
import ai.careerpilot.workflow.event.InterviewDetectedEvent;
import ai.careerpilot.workflow.event.InterviewTrackedEvent;
import ai.careerpilot.workflow.event.OfferReceivedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.Mockito.*;

/** Verifies every listener maps its source event onto the correct {@link LearningEventType}. */
class LearningEventBridgeTest {

    private LearningPipeline pipeline;
    private LearningEventBridge bridge;

    private final UUID userId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();
    private final UUID correlationId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        pipeline = mock(LearningPipeline.class);
        bridge = new LearningEventBridge(pipeline);
    }

    @Test
    void applicationSubmittedMapsCorrectly() {
        bridge.onApplicationSubmitted(new ApplicationSubmittedEvent(userId, jobId, UUID.randomUUID(), UUID.randomUUID(), "greenhouse"));
        verify(pipeline).capture(LearningEventType.APPLICATION_SUBMITTED, null, userId, jobId, null, null);
    }

    @Test
    void applicationRejectedMapsCorrectly() {
        bridge.onApplicationRejected(new ApplicationRejectedEvent(UUID.randomUUID(), correlationId, userId, jobId, UUID.randomUUID(), Instant.now(), "no fit"));
        verify(pipeline).capture(LearningEventType.APPLICATION_REJECTED, correlationId, userId, jobId, null, null);
    }

    @Test
    void applicationAcceptedMapsToBothAcceptedTypes() {
        bridge.onApplicationAccepted(new ApplicationAcceptedEvent(UUID.randomUUID(), correlationId, userId, jobId, UUID.randomUUID(), Instant.now()));
        verify(pipeline).capture(LearningEventType.APPLICATION_ACCEPTED, correlationId, userId, jobId, null, null);
        verify(pipeline).capture(LearningEventType.OFFER_ACCEPTED, correlationId, userId, jobId, null, null);
    }

    @Test
    void interviewDetectedMapsToScheduled() {
        bridge.onInterviewDetected(new InterviewDetectedEvent(UUID.randomUUID(), correlationId, userId, jobId, UUID.randomUUID(), Instant.now(), "TECHNICAL"));
        verify(pipeline).capture(LearningEventType.INTERVIEW_SCHEDULED, correlationId, userId, jobId, null, null);
    }

    @Test
    void interviewTrackedMapsToCompleted() {
        bridge.onInterviewTracked(new InterviewTrackedEvent(UUID.randomUUID(), correlationId, userId, jobId, UUID.randomUUID(), Instant.now(), UUID.randomUUID(), "PASSED"));
        verify(pipeline).capture(LearningEventType.INTERVIEW_COMPLETED, correlationId, userId, jobId, null, null);
    }

    @Test
    void offerReceivedMapsCorrectly() {
        bridge.onOfferReceived(new OfferReceivedEvent(UUID.randomUUID(), correlationId, userId, jobId, UUID.randomUUID(), Instant.now(), "150000"));
        verify(pipeline).capture(LearningEventType.OFFER_RECEIVED, correlationId, userId, jobId, null, null);
    }

    @Test
    void resumeTailoredMapsToResumeSelectedWithVersionFromTailoringId() {
        UUID tailoringId = UUID.randomUUID();
        bridge.onResumeTailored(new ResumeTailoredEvent(userId, jobId, tailoringId, UUID.randomUUID()));
        verify(pipeline).capture(LearningEventType.RESUME_SELECTED, null, userId, jobId, tailoringId.toString(), null);
    }

    @Test
    void recommendationApprovedMapsCorrectly() {
        bridge.onRecommendationApproved(new RecommendationApprovedEvent(userId, UUID.randomUUID(), jobId, UUID.randomUUID(), UUID.randomUUID()));
        verify(pipeline).capture(LearningEventType.RECOMMENDATION_APPROVED, null, userId, jobId, null, null);
    }

    @Test
    void analyticsComputedMapsToWorkflowCompleted() {
        bridge.onAnalyticsComputed(new AnalyticsComputedEvent(UUID.randomUUID(), correlationId, userId, jobId, UUID.randomUUID(), Instant.now()));
        verify(pipeline).capture(LearningEventType.WORKFLOW_COMPLETED, correlationId, userId, jobId, null, correlationId.toString());
    }
}
