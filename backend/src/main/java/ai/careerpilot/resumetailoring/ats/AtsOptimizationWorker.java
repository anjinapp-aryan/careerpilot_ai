package ai.careerpilot.resumetailoring.ats;

import ai.careerpilot.domain.AtsOptimizationJob;
import ai.careerpilot.resumetailoring.event.ResumeTailoredEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Phase 2D.2 — completes the event pipeline gap identified in the Phase 2D audit: reacts to
 * {@link ResumeTailoredEvent} (published by {@code ResumeTailoringJobService} once a tailoring
 * succeeds) and enqueues an ATS analysis of that tailored resume, mirroring {@code
 * ResumeTailoringWorker}'s own decoupling exactly:
 *
 * <pre>
 * ResumeTailored -&gt; (this worker, async, after commit) -&gt; AtsOptimizationJobService.enqueue()
 * -&gt; bounded executor -&gt; AtsOptimizationService.analyze() -&gt; persist ResumeAtsAnalysis
 * </pre>
 *
 * <b>After commit + async</b> so ATS analysis never adds latency to the tailoring flow, and
 * <b>failure-isolated</b> — any exception here is caught and logged, never propagated, so an ATS
 * failure (or the engine being disabled) can never affect the tailoring that triggered it. Gated
 * by its own {@code ats.optimization.trigger-on-tailoring.enabled} flag, independent of {@code
 * ats.optimization.enabled} — the engine stays manually testable via the API before the
 * auto-trigger is turned on, same two-flag pattern used for Resume Tailoring's own approve trigger.
 */
@Component
public class AtsOptimizationWorker {

    private static final Logger log = LoggerFactory.getLogger(AtsOptimizationWorker.class);

    private final AtsOptimizationService optimization;
    private final AtsOptimizationJobService jobs;
    private final boolean triggerOnTailoring;

    public AtsOptimizationWorker(AtsOptimizationService optimization, AtsOptimizationJobService jobs,
                                 @Value("${ats.optimization.trigger-on-tailoring.enabled:false}") boolean triggerOnTailoring) {
        this.optimization = optimization;
        this.jobs = jobs;
        this.triggerOnTailoring = triggerOnTailoring;
    }

    @Async(AtsOptimizationAsyncConfig.EXECUTOR_BEAN_NAME)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onResumeTailored(ResumeTailoredEvent event) {
        if (!triggerOnTailoring || !optimization.isEnabled()) return;
        try {
            jobs.enqueue(event.userId(), event.jobId(), AtsOptimizationJob.SOURCE_TAILORING_TRIGGER);
        } catch (Exception e) {
            log.warn("ATS optimization worker failed (user={}, job={}): {}",
                    event.userId(), event.jobId(), e.toString());
        }
    }
}
