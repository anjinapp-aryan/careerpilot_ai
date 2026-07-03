package ai.careerpilot.resumetailoring.service;

import ai.careerpilot.domain.ResumeTailoringJob;
import ai.careerpilot.resumetailoring.event.RecommendationApprovedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Phase 2D.1 decision #4 — Resume Tailoring must never run synchronously inside
 * {@code RecommendationDecisionService.decide()} (approve is already Neon-RTT-bound per the
 * Phase 2C sign-off; embedding an LLM call in that path would multiply its latency). Instead:
 *
 * <pre>
 * Approve -&gt; Application created -&gt; RecommendationApprovedEvent published -&gt; (this worker,
 * async, after commit) -&gt; ResumeTailoringJobService.enqueue() -&gt; bounded executor ->
 * ResumeTailoringService.tailor() -&gt; persist ResumeTailoring
 * </pre>
 *
 * Decoupled exactly like {@code CandidateProfileEventListener} / {@code ResumeEmbeddingListener}:
 * <b>after commit + async</b> so tailoring never adds latency to the approval response, and
 * <b>failure-isolated</b> — any exception here is caught and logged, never propagated, so a
 * tailoring failure (or the engine being disabled) can never affect the approval that triggered
 * it. Gated by its own {@code resume.tailoring.trigger-on-approve.enabled} flag, independent of
 * {@code resume.tailoring.enabled} (the engine can be manually testable via the API before the
 * auto-trigger on approve is turned on).
 *
 * <p>Phase 2D.1.1 — this listener no longer calls {@code ResumeTailoringService.tailor()}
 * directly (which used to run on Spring's unbounded default {@code @Async} executor). It now
 * hands off to {@link ResumeTailoringJobService#enqueue}, which runs the actual generation on the
 * dedicated bounded {@code resumeTailoringExecutor}. This listener method itself stays cheap
 * (persist a QUEUED row + submit) so keeping it {@code @Async} still costs nothing and preserves
 * the already-live-proven "approval never blocks" guarantee.
 */
@Component
public class ResumeTailoringWorker {

    private static final Logger log = LoggerFactory.getLogger(ResumeTailoringWorker.class);

    private final ResumeTailoringService tailoring;
    private final ResumeTailoringJobService jobs;
    private final boolean triggerOnApprove;

    public ResumeTailoringWorker(ResumeTailoringService tailoring, ResumeTailoringJobService jobs,
                                 @Value("${resume.tailoring.trigger-on-approve.enabled:false}") boolean triggerOnApprove) {
        this.tailoring = tailoring;
        this.jobs = jobs;
        this.triggerOnApprove = triggerOnApprove;
    }

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRecommendationApproved(RecommendationApprovedEvent event) {
        if (!triggerOnApprove || !tailoring.isEnabled()) return;
        try {
            jobs.enqueue(event.userId(), event.jobId(), event.recommendationAuditId(),
                    ResumeTailoringJob.SOURCE_APPROVE_TRIGGER);
        } catch (Exception e) {
            log.warn("Resume tailoring worker failed (user={}, job={}): {}",
                    event.userId(), event.jobId(), e.toString());
        }
    }
}
