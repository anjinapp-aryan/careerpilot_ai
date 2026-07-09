package ai.careerpilot.workflow.email;

import ai.careerpilot.domain.ApplicationEmail;
import ai.careerpilot.domain.WorkflowCorrelation;
import ai.careerpilot.workflow.config.WorkflowExecutorsConfig;
import ai.careerpilot.workflow.correlation.WorkflowCorrelationService;
import ai.careerpilot.workflow.correlation.WorkflowDeadLetterService;
import ai.careerpilot.workflow.event.EmailProcessedEvent;
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
 * Phase 3A.3 — pipeline stage 4. Consumes {@link TimelineUpdatedEvent}. Because there is NO mailbox
 * integration in this build, there is no email to classify here — the worker advances the correlation
 * and emits a neutral {@link EmailProcessedEvent} to keep the workflow flowing. Real classification
 * runs through {@code EmailIntelligenceService.process(...)} (the dark manual ingest path), which is
 * exercised directly and by unit tests. Dedicated {@code emailIntelligenceExecutor}; failure-isolated;
 * gated by {@code email.intelligence.trigger.enabled}.
 */
@Component
public class EmailIntelligenceWorker extends AbstractWorkflowWorker {

    static final String WORKFLOW = "email-intelligence";

    private final EmailIntelligenceService email;
    private final WorkflowCorrelationService correlation;
    private final ApplicationEventPublisher events;
    private final boolean triggerEnabled;

    public EmailIntelligenceWorker(EmailIntelligenceService email,
                                   WorkflowCorrelationService correlation,
                                   WorkflowDeadLetterService deadLetter,
                                   ApplicationEventPublisher events,
                                   @Qualifier(WorkflowExecutorsConfig.EMAIL_INTELLIGENCE_EXECUTOR) ThreadPoolTaskExecutor executor,
                                   @Value("${email.intelligence.trigger.enabled:false}") boolean triggerEnabled) {
        super(executor, deadLetter, WORKFLOW);
        this.email = email;
        this.correlation = correlation;
        this.events = events;
        this.triggerEnabled = triggerEnabled;
    }

    @Async(WorkflowExecutorsConfig.EMAIL_INTELLIGENCE_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onTimelineUpdated(TimelineUpdatedEvent event) {
        if (!triggerEnabled || !email.isEnabled()) return;
        dispatch(event, "email-intelligence", () -> {
            correlation.advance(event.correlationId(), "EMAIL_INTELLIGENCE", WorkflowCorrelation.STATUS_IN_PROGRESS);
            events.publishEvent(EmailProcessedEvent.from(event, ApplicationEmail.CATEGORY_UNKNOWN, 0.0));
        });
    }
}
