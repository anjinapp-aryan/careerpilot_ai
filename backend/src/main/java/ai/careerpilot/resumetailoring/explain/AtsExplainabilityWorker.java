package ai.careerpilot.resumetailoring.explain;

import ai.careerpilot.resumetailoring.config.PipelineExecutorsConfig;
import ai.careerpilot.resumetailoring.event.GapAnalysisCompletedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Phase 2D.4 — reacts to {@link GapAnalysisCompletedEvent} and runs the deterministic ATS
 * explanation on the dedicated bounded {@code atsExplainabilityExecutor}. Async, after-commit,
 * failure-isolated; gated by {@code ats.explainability.trigger.enabled}, independent of
 * {@code ats.explainability.enabled}.
 */
@Component
public class AtsExplainabilityWorker {

    private static final Logger log = LoggerFactory.getLogger(AtsExplainabilityWorker.class);

    private final AtsExplainabilityService explainability;
    private final ThreadPoolTaskExecutor executor;
    private final boolean triggerEnabled;

    public AtsExplainabilityWorker(AtsExplainabilityService explainability,
                                   @Qualifier(PipelineExecutorsConfig.ATS_EXPLAINABILITY_EXECUTOR) ThreadPoolTaskExecutor executor,
                                   @Value("${ats.explainability.trigger.enabled:false}") boolean triggerEnabled) {
        this.explainability = explainability;
        this.executor = executor;
        this.triggerEnabled = triggerEnabled;
    }

    @Async(PipelineExecutorsConfig.ATS_EXPLAINABILITY_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onGapAnalysisCompleted(GapAnalysisCompletedEvent event) {
        if (!triggerEnabled || !explainability.isEnabled()) return;
        try {
            executor.execute(() -> explainability.explain(event.userId(), event.jobId(),
                    event.resumeTailoringId(), event.atsAnalysisId(), event.gapAnalysisId()));
        } catch (Exception e) {
            log.warn("ATS explainability worker failed (user={}, job={}): {}",
                    event.userId(), event.jobId(), e.toString());
        }
    }
}
