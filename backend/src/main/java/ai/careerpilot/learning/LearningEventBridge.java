package ai.careerpilot.learning;

import ai.careerpilot.execution.event.ApplicationSubmittedEvent;
import ai.careerpilot.learning.config.LearningExecutorsConfig;
import ai.careerpilot.resumetailoring.event.RecommendationApprovedEvent;
import ai.careerpilot.resumetailoring.event.ResumeTailoredEvent;
import ai.careerpilot.workflow.event.AnalyticsComputedEvent;
import ai.careerpilot.workflow.event.ApplicationAcceptedEvent;
import ai.careerpilot.workflow.event.ApplicationRejectedEvent;
import ai.careerpilot.workflow.event.InterviewDetectedEvent;
import ai.careerpilot.workflow.event.InterviewTrackedEvent;
import ai.careerpilot.workflow.event.OfferReceivedEvent;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Phase 6.1 — a SECOND, purely-additive listener on top of every existing publisher (2D/2E/3A).
 * No existing event, worker, or service is modified; this class only observes. Every listener
 * follows the shared shape: async on the dedicated {@code learningExecutor}, after-commit, and
 * delegates all gating/error-isolation to {@link LearningPipeline#capture}, which never throws.
 *
 * <p>Not every {@link LearningEventType} has a source here — see that enum's javadoc for the
 * three values with no existing event to attach to. Two modeling choices worth noting:
 * <ul>
 *   <li>{@link ApplicationAcceptedEvent} is captured as BOTH {@code APPLICATION_ACCEPTED} and
 *       {@code OFFER_ACCEPTED} — in the Phase 3A state machine, ACCEPTED is only reachable from
 *       OFFER_RECEIVED, so this event is a reliable proxy for "the offer was accepted".</li>
 *   <li>{@link ResumeTailoredEvent} is captured as {@code RESUME_SELECTED} (a resume version was
 *       tailored/selected for a job) — there is no separate "resume rejected" signal.</li>
 * </ul>
 */
@Component
public class LearningEventBridge {

    private final LearningPipeline pipeline;

    public LearningEventBridge(LearningPipeline pipeline) {
        this.pipeline = pipeline;
    }

    @Async(LearningExecutorsConfig.LEARNING_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onApplicationSubmitted(ApplicationSubmittedEvent event) {
        pipeline.capture(LearningEventType.APPLICATION_SUBMITTED, null, event.userId(), event.jobId(), null, null);
    }

    @Async(LearningExecutorsConfig.LEARNING_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onApplicationRejected(ApplicationRejectedEvent event) {
        pipeline.capture(LearningEventType.APPLICATION_REJECTED, event.correlationId(), event.userId(), event.jobId(), null, null);
    }

    @Async(LearningExecutorsConfig.LEARNING_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onApplicationAccepted(ApplicationAcceptedEvent event) {
        pipeline.capture(LearningEventType.APPLICATION_ACCEPTED, event.correlationId(), event.userId(), event.jobId(), null, null);
        pipeline.capture(LearningEventType.OFFER_ACCEPTED, event.correlationId(), event.userId(), event.jobId(), null, null);
    }

    @Async(LearningExecutorsConfig.LEARNING_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onInterviewDetected(InterviewDetectedEvent event) {
        pipeline.capture(LearningEventType.INTERVIEW_SCHEDULED, event.correlationId(), event.userId(), event.jobId(), null, null);
    }

    @Async(LearningExecutorsConfig.LEARNING_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onInterviewTracked(InterviewTrackedEvent event) {
        pipeline.capture(LearningEventType.INTERVIEW_COMPLETED, event.correlationId(), event.userId(), event.jobId(), null, null);
    }

    @Async(LearningExecutorsConfig.LEARNING_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onOfferReceived(OfferReceivedEvent event) {
        pipeline.capture(LearningEventType.OFFER_RECEIVED, event.correlationId(), event.userId(), event.jobId(), null, null);
    }

    @Async(LearningExecutorsConfig.LEARNING_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onResumeTailored(ResumeTailoredEvent event) {
        String resumeVersion = event.resumeTailoringId() == null ? null : event.resumeTailoringId().toString();
        pipeline.capture(LearningEventType.RESUME_SELECTED, null, event.userId(), event.jobId(), resumeVersion, null);
    }

    @Async(LearningExecutorsConfig.LEARNING_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRecommendationApproved(RecommendationApprovedEvent event) {
        pipeline.capture(LearningEventType.RECOMMENDATION_APPROVED, null, event.userId(), event.jobId(), null, null);
    }

    @Async(LearningExecutorsConfig.LEARNING_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAnalyticsComputed(AnalyticsComputedEvent event) {
        String workflowId = event.correlationId() == null ? null : event.correlationId().toString();
        pipeline.capture(LearningEventType.WORKFLOW_COMPLETED, event.correlationId(), event.userId(), event.jobId(), null, workflowId);
    }
}
