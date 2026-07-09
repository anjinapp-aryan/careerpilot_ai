package ai.careerpilot.workflow.interview;

import ai.careerpilot.domain.ApplicationEmail;
import ai.careerpilot.domain.Interview;
import ai.careerpilot.domain.WorkflowCorrelation;
import ai.careerpilot.workflow.config.WorkflowExecutorsConfig;
import ai.careerpilot.workflow.correlation.WorkflowCorrelationService;
import ai.careerpilot.workflow.correlation.WorkflowDeadLetterService;
import ai.careerpilot.workflow.event.EmailProcessedEvent;
import ai.careerpilot.workflow.event.InterviewDetectedEvent;
import ai.careerpilot.workflow.worker.AbstractWorkflowWorker;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Phase 3A.4 — pipeline stage 5. Consumes {@link EmailProcessedEvent} and derives whether it signals
 * an interview. Always emits {@link InterviewDetectedEvent} to keep the linear workflow flowing
 * ({@code interviewType = "NONE"} when nothing is detected). Dedicated {@code interviewTrackingExecutor};
 * failure-isolated; gated by {@code interview.tracking.trigger.enabled}.
 */
@Component
public class InterviewDetectionWorker extends AbstractWorkflowWorker {

    static final String WORKFLOW = "interview-detection";
    static final String NONE = "NONE";

    private final InterviewService interview;
    private final WorkflowCorrelationService correlation;
    private final ApplicationEventPublisher events;
    private final boolean triggerEnabled;

    public InterviewDetectionWorker(InterviewService interview,
                                    WorkflowCorrelationService correlation,
                                    WorkflowDeadLetterService deadLetter,
                                    ApplicationEventPublisher events,
                                    @Qualifier(WorkflowExecutorsConfig.INTERVIEW_TRACKING_EXECUTOR) ThreadPoolTaskExecutor executor,
                                    @Value("${interview.tracking.trigger.enabled:false}") boolean triggerEnabled) {
        super(executor, deadLetter, WORKFLOW);
        this.interview = interview;
        this.correlation = correlation;
        this.events = events;
        this.triggerEnabled = triggerEnabled;
    }

    @Async(WorkflowExecutorsConfig.INTERVIEW_TRACKING_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onEmailProcessed(EmailProcessedEvent event) {
        if (!triggerEnabled || !interview.isEnabled()) return;
        dispatch(event, "detect-interview", () -> {
            String type = ApplicationEmail.CATEGORY_INTERVIEW.equals(event.category())
                    ? Interview.TYPE_TECHNICAL : NONE;
            if (!NONE.equals(type)) interview.markDetected();
            correlation.advance(event.correlationId(), "INTERVIEW_DETECTION", WorkflowCorrelation.STATUS_IN_PROGRESS);
            events.publishEvent(InterviewDetectedEvent.from(event, type));
        });
    }
}
