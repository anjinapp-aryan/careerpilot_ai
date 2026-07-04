package ai.careerpilot.workflow.career;

import ai.careerpilot.domain.WorkflowCorrelation;
import ai.careerpilot.workflow.config.WorkflowExecutorsConfig;
import ai.careerpilot.workflow.correlation.WorkflowCorrelationService;
import ai.careerpilot.workflow.correlation.WorkflowDeadLetterService;
import ai.careerpilot.workflow.event.AnalyticsComputedEvent;
import ai.careerpilot.workflow.worker.AbstractWorkflowWorker;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Phase 3A.6 — pipeline stage 9 (TERMINAL — publishes nothing further). Consumes
 * {@link AnalyticsComputedEvent}, recomputes the user's career probabilities, and closes the workflow
 * by marking the correlation COMPLETED. Dedicated {@code careerIntelligenceExecutor}; failure-isolated;
 * gated by {@code career.intelligence.trigger.enabled}.
 */
@Component
public class CareerIntelligenceWorker extends AbstractWorkflowWorker {

    static final String WORKFLOW = "career-intelligence";

    private final CareerIntelligenceService career;
    private final WorkflowCorrelationService correlation;
    private final boolean triggerEnabled;

    public CareerIntelligenceWorker(CareerIntelligenceService career,
                                    WorkflowCorrelationService correlation,
                                    WorkflowDeadLetterService deadLetter,
                                    @Qualifier(WorkflowExecutorsConfig.CAREER_INTELLIGENCE_EXECUTOR) ThreadPoolTaskExecutor executor,
                                    @Value("${career.intelligence.trigger.enabled:false}") boolean triggerEnabled) {
        super(executor, deadLetter, WORKFLOW);
        this.career = career;
        this.correlation = correlation;
        this.triggerEnabled = triggerEnabled;
    }

    @Async(WorkflowExecutorsConfig.CAREER_INTELLIGENCE_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAnalyticsComputed(AnalyticsComputedEvent event) {
        if (!triggerEnabled || !career.isEnabled()) return;
        dispatch(event, "career-intelligence", () -> {
            career.recompute(event.userId());
            correlation.advance(event.correlationId(), "CAREER_INTELLIGENCE", WorkflowCorrelation.STATUS_COMPLETED);
        });
    }
}
