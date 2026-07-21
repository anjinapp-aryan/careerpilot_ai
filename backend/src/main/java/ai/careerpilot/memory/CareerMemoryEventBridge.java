package ai.careerpilot.memory;

import ai.careerpilot.memory.config.CareerMemoryExecutorConfig;
import ai.careerpilot.resumetailoring.event.RecommendationApprovedEvent;
import ai.careerpilot.workflow.event.ApplicationAcceptedEvent;
import ai.careerpilot.workflow.event.ApplicationRejectedEvent;
import ai.careerpilot.workflow.event.InterviewDetectedEvent;
import ai.careerpilot.workflow.event.OfferReceivedEvent;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;

/**
 * A THIRD purely-additive listener on the existing Phase 2D/3A event bus — same pattern as
 * {@code LearningEventBridge} (the second one). No existing publisher, worker, or service is
 * modified; this only observes. {@link ai.careerpilot.service.RecommendationFeedbackService} is
 * the other extraction source, hooked directly there instead of via an event because that
 * service doesn't publish one (see its {@code captureFeedback} call site).
 *
 * <p>Only {@link ApplicationRejectedEvent} carries a free-text reason today — the other four
 * events record the decision without one, which is honest: no reason was actually captured, so
 * {@code CareerMemoryService.record} is called with {@code reason=null} rather than fabricating one.
 */
@Component
public class CareerMemoryEventBridge {

    private final CareerMemoryService memory;

    public CareerMemoryEventBridge(CareerMemoryService memory) {
        this.memory = memory;
    }

    @Async(CareerMemoryExecutorConfig.CAREER_MEMORY_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onApplicationRejected(ApplicationRejectedEvent event) {
        memory.record(event.userId(), "APPLICATION_REJECTED", "APPLICATION", null, event.reason(),
                BigDecimal.ONE, "APPLICATION_LIFECYCLE", event.reason() != null ? 5 : 3, false,
                event.jobId(), event.correlationId(), null, null);
    }

    @Async(CareerMemoryExecutorConfig.CAREER_MEMORY_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onApplicationAccepted(ApplicationAcceptedEvent event) {
        memory.record(event.userId(), "OFFER_ACCEPTED", "CAREER", null, null,
                BigDecimal.ONE, "APPLICATION_LIFECYCLE", 5, false,
                event.jobId(), event.correlationId(), null, null);
    }

    @Async(CareerMemoryExecutorConfig.CAREER_MEMORY_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onInterviewDetected(InterviewDetectedEvent event) {
        memory.record(event.userId(), "INTERVIEW_SCHEDULED", "INTERVIEW", null, null,
                BigDecimal.valueOf(0.9), "INTERVIEW_INTELLIGENCE", 4, true,
                event.jobId(), event.correlationId(), null, null);
    }

    @Async(CareerMemoryExecutorConfig.CAREER_MEMORY_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onOfferReceived(OfferReceivedEvent event) {
        memory.record(event.userId(), "OFFER_RECEIVED", "SALARY", null, null,
                BigDecimal.ONE, "OFFER_INTELLIGENCE", 5, false,
                event.jobId(), event.correlationId(), null, null);
    }

    @Async(CareerMemoryExecutorConfig.CAREER_MEMORY_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRecommendationApproved(RecommendationApprovedEvent event) {
        memory.record(event.userId(), "JOB_APPROVED", "APPLICATION", null, null,
                BigDecimal.ONE, "RECOMMENDATION_ENGINE", 3, false,
                event.jobId(), null, null, null);
    }
}
