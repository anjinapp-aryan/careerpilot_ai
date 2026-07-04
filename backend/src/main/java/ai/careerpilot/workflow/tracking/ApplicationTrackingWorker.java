package ai.careerpilot.workflow.tracking;

import ai.careerpilot.domain.ApplicationLifecycle;
import ai.careerpilot.domain.WorkflowCorrelation;
import ai.careerpilot.workflow.config.WorkflowExecutorsConfig;
import ai.careerpilot.workflow.correlation.WorkflowCorrelationService;
import ai.careerpilot.workflow.correlation.WorkflowDeadLetterService;
import ai.careerpilot.workflow.event.ApplicationCreatedEvent;
import ai.careerpilot.workflow.event.ApplicationTrackedEvent;
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
 * Phase 3A.1 — pipeline stage 1. Consumes the entry {@link ApplicationCreatedEvent}, opens the
 * lifecycle row, advances the correlation, and emits {@link ApplicationTrackedEvent}. Async,
 * after-commit, failure-isolated (a failure becomes a dead-letter row via
 * {@link AbstractWorkflowWorker}); gated by {@code workflow.tracking.trigger.enabled}.
 */
@Component
public class ApplicationTrackingWorker extends AbstractWorkflowWorker {

    static final String WORKFLOW = "application-tracking";

    private final ApplicationLifecycleService lifecycle;
    private final WorkflowCorrelationService correlation;
    private final ApplicationEventPublisher events;
    private final boolean triggerEnabled;

    public ApplicationTrackingWorker(ApplicationLifecycleService lifecycle,
                                     WorkflowCorrelationService correlation,
                                     WorkflowDeadLetterService deadLetter,
                                     ApplicationEventPublisher events,
                                     @Qualifier(WorkflowExecutorsConfig.APPLICATION_TRACKING_EXECUTOR) ThreadPoolTaskExecutor executor,
                                     @Value("${workflow.tracking.trigger.enabled:false}") boolean triggerEnabled) {
        super(executor, deadLetter, WORKFLOW);
        this.lifecycle = lifecycle;
        this.correlation = correlation;
        this.events = events;
        this.triggerEnabled = triggerEnabled;
    }

    @Async(WorkflowExecutorsConfig.APPLICATION_TRACKING_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onApplicationCreated(ApplicationCreatedEvent event) {
        if (!triggerEnabled || !lifecycle.isEnabled()) return;
        dispatch(event, "track", () -> {
            lifecycle.createOrGet(event.userId(), event.jobId(), event.applicationId(),
                    event.company(), event.country(), event.source());
            correlation.advance(event.correlationId(), "TRACKING", WorkflowCorrelation.STATUS_IN_PROGRESS);
            events.publishEvent(ApplicationTrackedEvent.from(event, ApplicationLifecycle.STATUS_SUBMITTED));
        });
    }
}
