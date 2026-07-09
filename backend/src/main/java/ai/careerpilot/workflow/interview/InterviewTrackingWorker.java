package ai.careerpilot.workflow.interview;

import ai.careerpilot.domain.Interview;
import ai.careerpilot.domain.WorkflowCorrelation;
import ai.careerpilot.workflow.config.WorkflowExecutorsConfig;
import ai.careerpilot.workflow.correlation.WorkflowCorrelationService;
import ai.careerpilot.workflow.correlation.WorkflowDeadLetterService;
import ai.careerpilot.workflow.event.InterviewDetectedEvent;
import ai.careerpilot.workflow.event.InterviewTrackedEvent;
import ai.careerpilot.workflow.worker.AbstractWorkflowWorker;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Optional;
import java.util.UUID;

/**
 * Phase 3A.4 — pipeline stage 6. Consumes {@link InterviewDetectedEvent}; when a real interview type
 * was detected it records the round via {@link InterviewService}. Always emits {@link InterviewTrackedEvent}
 * to keep the workflow flowing to offer detection. Dedicated {@code interviewTrackingExecutor};
 * failure-isolated; gated by {@code interview.tracking.trigger.enabled}.
 */
@Component
public class InterviewTrackingWorker extends AbstractWorkflowWorker {

    static final String WORKFLOW = "interview-tracking";

    private final InterviewService interview;
    private final WorkflowCorrelationService correlation;
    private final ApplicationEventPublisher events;
    private final boolean triggerEnabled;

    public InterviewTrackingWorker(InterviewService interview,
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
    public void onInterviewDetected(InterviewDetectedEvent event) {
        if (!triggerEnabled || !interview.isEnabled()) return;
        dispatch(event, "track-interview", () -> {
            UUID interviewId = null;
            String result = Interview.RESULT_PENDING;
            if (!InterviewDetectionWorker.NONE.equals(event.interviewType())) {
                Optional<Interview> row = interview.record(event.userId(), event.jobId(),
                        event.interviewType(), null, null, Interview.RESULT_SCHEDULED);
                if (row.isPresent()) {
                    interviewId = row.get().getId();
                    result = row.get().getResult();
                }
            }
            correlation.advance(event.correlationId(), "INTERVIEW_TRACKING", WorkflowCorrelation.STATUS_IN_PROGRESS);
            events.publishEvent(InterviewTrackedEvent.from(event, interviewId, result));
        });
    }
}
