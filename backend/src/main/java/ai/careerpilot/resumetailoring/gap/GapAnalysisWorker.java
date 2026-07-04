package ai.careerpilot.resumetailoring.gap;

import ai.careerpilot.resumetailoring.config.PipelineExecutorsConfig;
import ai.careerpilot.resumetailoring.event.AtsOptimizedEvent;
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
 * Phase 2D.3 — reacts to {@link AtsOptimizedEvent} and runs the deterministic gap analysis on the
 * dedicated bounded {@code gapAnalysisExecutor}. Same decoupling contract as every pipeline
 * worker: async, after-commit, failure-isolated — any exception (including a saturated queue's
 * {@code TaskRejectedException}) is caught and logged, never propagated, so a gap-analysis failure
 * can never affect the ATS analysis that published the event. Gated by its own
 * {@code gap.analysis.trigger.enabled} flag, independent of {@code gap.analysis.enabled}.
 */
@Component
public class GapAnalysisWorker {

    private static final Logger log = LoggerFactory.getLogger(GapAnalysisWorker.class);

    private final GapAnalysisService gapAnalysis;
    private final ThreadPoolTaskExecutor executor;
    private final boolean triggerEnabled;

    public GapAnalysisWorker(GapAnalysisService gapAnalysis,
                             @Qualifier(PipelineExecutorsConfig.GAP_ANALYSIS_EXECUTOR) ThreadPoolTaskExecutor executor,
                             @Value("${gap.analysis.trigger.enabled:false}") boolean triggerEnabled) {
        this.gapAnalysis = gapAnalysis;
        this.executor = executor;
        this.triggerEnabled = triggerEnabled;
    }

    @Async(PipelineExecutorsConfig.GAP_ANALYSIS_EXECUTOR)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAtsOptimized(AtsOptimizedEvent event) {
        if (!triggerEnabled || !gapAnalysis.isEnabled()) return;
        try {
            executor.execute(() -> gapAnalysis.analyze(
                    event.userId(), event.jobId(), event.resumeTailoringId(), event.atsAnalysisId()));
        } catch (Exception e) {
            log.warn("Gap analysis worker failed (user={}, job={}): {}",
                    event.userId(), event.jobId(), e.toString());
        }
    }
}
