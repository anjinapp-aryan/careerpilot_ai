package ai.careerpilot.workflow.analytics;

import ai.careerpilot.domain.WorkflowCorrelation;
import ai.careerpilot.workflow.config.WorkflowExecutorsConfig;
import ai.careerpilot.workflow.correlation.WorkflowCorrelationService;
import ai.careerpilot.workflow.correlation.WorkflowDeadLetterService;
import ai.careerpilot.workflow.event.AnalyticsComputedEvent;
import ai.careerpilot.workflow.event.ApplicationAcceptedEvent;
import ai.careerpilot.workflow.event.ApplicationRejectedEvent;
import ai.careerpilot.workflow.event.BaseWorkflowEvent;
import ai.careerpilot.workflow.event.OfferReceivedEvent;
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
 * Phase 3A.5 — pipeline stage 8. Recomputes a user's outcome analytics whenever an application reaches
 * a terminal outcome ({@link OfferReceivedEvent} / {@link ApplicationAcceptedEvent} /
 * {@link ApplicationRejectedEvent} — exactly when success rates change) and emits
 * {@link AnalyticsComputedEvent} to feed career intelligence. Dedicated {@code careerAnalyticsExecutor};
 * failure-isolated; gated by {@code workflow.analytics.trigger.enabled}.
 *
 * <p>Explicitly named {@code careerAnalyticsWorker} (not the default {@code analyticsWorker}) — it
 * would otherwise collide, at full component-scan boot, with {@link ai.careerpilot.execution.analytics.AnalyticsWorker}
 * (Phase 2E), which also defaults to bean name {@code analyticsWorker}. Same collision-avoidance
 * convention already applied to the {@code careerAnalyticsExecutor} bean; 2E is left untouched.
 */
@Component("careerAnalyticsWorker")
public class AnalyticsWorker extends AbstractWorkflowWorker {

    static final String WORKFLOW = "application-analytics";

    private final ApplicationAnalyticsService analytics;
    private final WorkflowCorrelationService correlation;
    private final ApplicationEventPublisher events;
    private final boolean triggerEnabled;

    public AnalyticsWorker(ApplicationAnalyticsService analytics,
                           WorkflowCorrelationService correlation,
                           WorkflowDeadLetterService deadLetter,
                           ApplicationEventPublisher events,
                           @Qualifier(WorkflowExecutorsConfig.CAREER_ANALYTICS_EXECUTOR) ThreadPoolTaskExecutor executor,
                           @Value("${workflow.analytics.trigger.enabled:false}") boolean triggerEnabled) {
        super(executor, deadLetter, WORKFLOW);
        this.analytics = analytics;
        this.correlation = correlation;
        this.events = events;
        this.triggerEnabled = triggerEnabled;
    }

    @Async(WorkflowExecutorsConfig.CAREER_ANALYTICS_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onOfferReceived(OfferReceivedEvent event) {
        recompute(event);
    }

    @Async(WorkflowExecutorsConfig.CAREER_ANALYTICS_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onApplicationAccepted(ApplicationAcceptedEvent event) {
        recompute(event);
    }

    @Async(WorkflowExecutorsConfig.CAREER_ANALYTICS_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onApplicationRejected(ApplicationRejectedEvent event) {
        recompute(event);
    }

    private void recompute(BaseWorkflowEvent event) {
        if (!triggerEnabled || !analytics.isEnabled()) return;
        dispatch(event, "analytics", () -> {
            analytics.recompute(event.userId());
            correlation.advance(event.correlationId(), "ANALYTICS", WorkflowCorrelation.STATUS_IN_PROGRESS);
            events.publishEvent(AnalyticsComputedEvent.from(event));
        });
    }
}
