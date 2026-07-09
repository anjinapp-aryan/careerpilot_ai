package ai.careerpilot.workflow.analytics;

import ai.careerpilot.domain.ApplicationLifecycle;
import ai.careerpilot.domain.WorkflowCorrelation;
import ai.careerpilot.workflow.config.WorkflowExecutorsConfig;
import ai.careerpilot.workflow.correlation.WorkflowCorrelationService;
import ai.careerpilot.workflow.correlation.WorkflowDeadLetterService;
import ai.careerpilot.workflow.event.ApplicationAcceptedEvent;
import ai.careerpilot.workflow.event.ApplicationRejectedEvent;
import ai.careerpilot.workflow.event.InterviewTrackedEvent;
import ai.careerpilot.workflow.event.OfferReceivedEvent;
import ai.careerpilot.workflow.tracking.ApplicationLifecycleService;
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
 * Phase 3A.5 — pipeline stage 7. Consumes {@link InterviewTrackedEvent} and inspects the application's
 * current lifecycle status to emit a TERMINAL outcome event only when one has genuinely been reached:
 * {@link OfferReceivedEvent} (OFFER_RECEIVED/NEGOTIATION), {@link ApplicationAcceptedEvent} (ACCEPTED),
 * or {@link ApplicationRejectedEvent} (REJECTED). It deliberately does NOT fabricate an offer when the
 * application is still in progress — the workflow simply completes at this stage in that case.
 * Dedicated {@code careerAnalyticsExecutor}; failure-isolated; gated by {@code workflow.analytics.trigger.enabled}.
 */
@Component
public class OfferDetectionWorker extends AbstractWorkflowWorker {

    static final String WORKFLOW = "offer-detection";

    private final ApplicationLifecycleService lifecycle;
    private final ApplicationAnalyticsService analytics;
    private final WorkflowCorrelationService correlation;
    private final ApplicationEventPublisher events;
    private final boolean triggerEnabled;

    public OfferDetectionWorker(ApplicationLifecycleService lifecycle,
                                ApplicationAnalyticsService analytics,
                                WorkflowCorrelationService correlation,
                                WorkflowDeadLetterService deadLetter,
                                ApplicationEventPublisher events,
                                @Qualifier(WorkflowExecutorsConfig.CAREER_ANALYTICS_EXECUTOR) ThreadPoolTaskExecutor executor,
                                @Value("${workflow.analytics.trigger.enabled:false}") boolean triggerEnabled) {
        super(executor, deadLetter, WORKFLOW);
        this.lifecycle = lifecycle;
        this.analytics = analytics;
        this.correlation = correlation;
        this.events = events;
        this.triggerEnabled = triggerEnabled;
    }

    @Async(WorkflowExecutorsConfig.CAREER_ANALYTICS_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onInterviewTracked(InterviewTrackedEvent event) {
        if (!triggerEnabled || !analytics.isEnabled()) return;
        dispatch(event, "detect-offer", () -> {
            Optional<ApplicationLifecycle> row = lifecycle.find(event.userId(), event.jobId());
            String status = row.map(ApplicationLifecycle::getCurrentStatus).orElse(null);
            correlation.advance(event.correlationId(), "OFFER_DETECTION", WorkflowCorrelation.STATUS_IN_PROGRESS);
            if (ApplicationLifecycle.STATUS_ACCEPTED.equals(status)) {
                events.publishEvent(ApplicationAcceptedEvent.from(event));
            } else if (ApplicationLifecycle.STATUS_REJECTED.equals(status)) {
                events.publishEvent(ApplicationRejectedEvent.from(event, "lifecycle rejected"));
            } else if (ApplicationLifecycle.STATUS_OFFER_RECEIVED.equals(status)
                    || ApplicationLifecycle.STATUS_NEGOTIATION.equals(status)) {
                events.publishEvent(OfferReceivedEvent.from(event, null));
            }
            // else: no terminal outcome yet — workflow completes here without fabricating one.
        });
    }
}
