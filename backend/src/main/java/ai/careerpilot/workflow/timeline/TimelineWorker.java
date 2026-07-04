package ai.careerpilot.workflow.timeline;

import ai.careerpilot.domain.ApplicationTimeline;
import ai.careerpilot.domain.WorkflowCorrelation;
import ai.careerpilot.workflow.config.WorkflowExecutorsConfig;
import ai.careerpilot.workflow.correlation.WorkflowCorrelationService;
import ai.careerpilot.workflow.correlation.WorkflowDeadLetterService;
import ai.careerpilot.workflow.event.StatusDetectedEvent;
import ai.careerpilot.workflow.event.TimelineUpdatedEvent;
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
 * Phase 3A.2 — pipeline stage 3. Consumes {@link StatusDetectedEvent}, appends a timeline entry for
 * the detected status, and emits {@link TimelineUpdatedEvent}. Dedicated {@code timelineExecutor};
 * failure-isolated to dead-letter; gated by {@code workflow.timeline.trigger.enabled}.
 */
@Component
public class TimelineWorker extends AbstractWorkflowWorker {

    static final String WORKFLOW = "application-timeline";

    private final TimelineService timeline;
    private final WorkflowCorrelationService correlation;
    private final ApplicationEventPublisher events;
    private final boolean triggerEnabled;

    public TimelineWorker(TimelineService timeline,
                          WorkflowCorrelationService correlation,
                          WorkflowDeadLetterService deadLetter,
                          ApplicationEventPublisher events,
                          @Qualifier(WorkflowExecutorsConfig.TIMELINE_EXECUTOR) ThreadPoolTaskExecutor executor,
                          @Value("${workflow.timeline.trigger.enabled:false}") boolean triggerEnabled) {
        super(executor, deadLetter, WORKFLOW);
        this.timeline = timeline;
        this.correlation = correlation;
        this.events = events;
        this.triggerEnabled = triggerEnabled;
    }

    @Async(WorkflowExecutorsConfig.TIMELINE_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onStatusDetected(StatusDetectedEvent event) {
        if (!triggerEnabled || !timeline.isEnabled()) return;
        dispatch(event, "timeline", () -> {
            timeline.append(event.userId(), event.jobId(), event.detectedStatus(),
                    ApplicationTimeline.SOURCE_STATUS_DETECTION, 1.0,
                    "from " + event.previousStatus() + " to " + event.detectedStatus());
            correlation.advance(event.correlationId(), "TIMELINE", WorkflowCorrelation.STATUS_IN_PROGRESS);
            events.publishEvent(TimelineUpdatedEvent.from(event, event.detectedStatus()));
        });
    }
}
