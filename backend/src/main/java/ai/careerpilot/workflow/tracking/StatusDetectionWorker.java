package ai.careerpilot.workflow.tracking;

import ai.careerpilot.domain.ApplicationLifecycle;
import ai.careerpilot.workflow.config.WorkflowExecutorsConfig;
import ai.careerpilot.workflow.correlation.WorkflowCorrelationService;
import ai.careerpilot.workflow.correlation.WorkflowDeadLetterService;
import ai.careerpilot.workflow.event.ApplicationTrackedEvent;
import ai.careerpilot.workflow.event.StatusDetectedEvent;
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

/**
 * Phase 3A.1 — pipeline stage 2. Consumes {@link ApplicationTrackedEvent} and derives the application's
 * next lifecycle status. In this build it is a deterministic pass-through: it re-affirms the current
 * persisted status (real status inference from external signals is a later, connector-gated concern),
 * advancing the correlation and emitting {@link StatusDetectedEvent}. On its own dedicated
 * {@code statusDetectionExecutor}; gated by {@code workflow.tracking.trigger.enabled}.
 */
@Component
public class StatusDetectionWorker extends AbstractWorkflowWorker {

    static final String WORKFLOW = "status-detection";

    private final ApplicationLifecycleService lifecycle;
    private final WorkflowCorrelationService correlation;
    private final ApplicationEventPublisher events;
    private final boolean triggerEnabled;

    public StatusDetectionWorker(ApplicationLifecycleService lifecycle,
                                 WorkflowCorrelationService correlation,
                                 WorkflowDeadLetterService deadLetter,
                                 ApplicationEventPublisher events,
                                 @Qualifier(WorkflowExecutorsConfig.STATUS_DETECTION_EXECUTOR) ThreadPoolTaskExecutor executor,
                                 @Value("${workflow.tracking.trigger.enabled:false}") boolean triggerEnabled) {
        super(executor, deadLetter, WORKFLOW);
        this.lifecycle = lifecycle;
        this.correlation = correlation;
        this.events = events;
        this.triggerEnabled = triggerEnabled;
    }

    @Async(WorkflowExecutorsConfig.STATUS_DETECTION_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onApplicationTracked(ApplicationTrackedEvent event) {
        if (!triggerEnabled || !lifecycle.isEnabled()) return;
        dispatch(event, "detect-status", () -> {
            Optional<ApplicationLifecycle> row = lifecycle.find(event.userId(), event.jobId());
            String current = row.map(ApplicationLifecycle::getCurrentStatus).orElse(event.status());
            String previous = row.map(ApplicationLifecycle::getPreviousStatus).orElse(null);
            correlation.advance(event.correlationId(), "STATUS_DETECTION",
                    ai.careerpilot.domain.WorkflowCorrelation.STATUS_IN_PROGRESS);
            events.publishEvent(StatusDetectedEvent.from(event, current, previous));
        });
    }
}
