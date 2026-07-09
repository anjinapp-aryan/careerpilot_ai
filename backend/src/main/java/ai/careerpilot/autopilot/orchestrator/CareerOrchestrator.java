package ai.careerpilot.autopilot.orchestrator;

import ai.careerpilot.autopilot.apply.AutoApplyEngine;
import ai.careerpilot.autopilot.decision.ApplicationDecisionEngine;
import ai.careerpilot.autopilot.decision.DecisionOutcome;
import ai.careerpilot.autopilot.provider.SubmissionStatus;
import ai.careerpilot.autopilot.resume.AutopilotTailoringTrigger;
import ai.careerpilot.domain.ApplicationDecision;
import ai.careerpilot.domain.ApplicationSubmission;
import ai.careerpilot.domain.JobRecommendation;
import ai.careerpilot.repo.JobRecommendationRepository;
import ai.careerpilot.workflow.correlation.WorkflowCorrelationService;
import ai.careerpilot.workflow.correlation.WorkflowDeadLetterService;
import ai.careerpilot.workflow.entry.WorkflowEntryBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Phase 7.10 — the autonomous career orchestrator: the deterministic spine that chains the
 * already-built Phase 7 engines per (user, job) and hangs every step off a Phase 3A correlation id
 * with per-job dead-letter isolation. It <em>connects</em>, it does not re-implement:
 *
 * <pre>
 * recommendation -> ApplicationDecisionEngine (7.1)
 *   AUTO_APPLY  -> AutopilotTailoringTrigger (7.3, reuses 2D pipeline) -> AutoApplyEngine (7.4)
 *                  -> genuine SUBMITTED starts Phase 3A tracking via WorkflowEntryBridge (7.5 reuse)
 *   HUMAN_REVIEW / SAVE / IGNORE -> recorded, no submission
 * </pre>
 *
 * <p>Each job runs in its own try/catch that routes failures to {@link WorkflowDeadLetterService}
 * so one bad job never aborts a user's run. Every sub-engine is independently flag-gated, so with
 * stock flags {@link #runForUser} decides nothing and applies nothing. Gated by
 * {@code career.orchestrator.enabled} (default off).
 */
@Service
public class CareerOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(CareerOrchestrator.class);
    private static final String WORKFLOW_TYPE = "career-orchestrator";

    private final JobRecommendationRepository recommendations;
    private final ApplicationDecisionEngine decisionEngine;
    private final AutopilotTailoringTrigger tailoringTrigger;
    private final AutoApplyEngine autoApplyEngine;
    private final WorkflowCorrelationService correlation;
    private final WorkflowDeadLetterService deadLetters;
    private final WorkflowEntryBridge trackingEntry;
    private final AutopilotMetrics metrics;
    private final boolean enabled;
    private final int maxPerUser;

    public CareerOrchestrator(JobRecommendationRepository recommendations,
                              ApplicationDecisionEngine decisionEngine,
                              AutopilotTailoringTrigger tailoringTrigger,
                              AutoApplyEngine autoApplyEngine,
                              WorkflowCorrelationService correlation,
                              WorkflowDeadLetterService deadLetters,
                              WorkflowEntryBridge trackingEntry,
                              AutopilotMetrics metrics,
                              @Value("${career.orchestrator.enabled:false}") boolean enabled,
                              @Value("${career.orchestrator.max-per-user:25}") int maxPerUser) {
        this.recommendations = recommendations;
        this.decisionEngine = decisionEngine;
        this.tailoringTrigger = tailoringTrigger;
        this.autoApplyEngine = autoApplyEngine;
        this.correlation = correlation;
        this.deadLetters = deadLetters;
        this.trackingEntry = trackingEntry;
        this.metrics = metrics;
        this.enabled = enabled;
        this.maxPerUser = maxPerUser;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Per-user tallies of one orchestrator run. */
    public record AutopilotRunSummary(int processed, int autoApplied, int humanReview, int saved,
                                      int ignored, int failed) {
        static AutopilotRunSummary disabled() { return new AutopilotRunSummary(0, 0, 0, 0, 0, 0); }
    }

    /** Run the apply-side pipeline for one user. No-op summary when disabled. Never throws. */
    public AutopilotRunSummary runForUser(UUID userId, UUID orgId) {
        if (!enabled) return AutopilotRunSummary.disabled();
        metrics.recordRun();
        int processed = 0, autoApplied = 0, humanReview = 0, saved = 0, ignored = 0, failed = 0;

        List<JobRecommendation> recs = recommendations.findByUserIdOrderByMatchScoreDesc(userId);
        for (JobRecommendation rec : recs.stream().limit(maxPerUser).toList()) {
            UUID jobId = rec.getJobId();
            UUID correlationId = null;
            try {
                correlationId = correlation.start(WORKFLOW_TYPE, userId, jobId, null);
                ApplicationDecision decision = decisionEngine.decide(userId, jobId).orElse(null);
                if (decision == null) {
                    correlation.advance(correlationId, "DECISION", "SKIPPED");
                    continue; // decision engine disabled, or no recommendation to decide on
                }
                processed++;
                metrics.recordJobProcessed();
                DecisionOutcome outcome = DecisionOutcome.valueOf(decision.getOutcome());
                metrics.recordDecision(outcome);
                correlation.advance(correlationId, "DECISION", outcome.name());

                switch (outcome) {
                    case AUTO_APPLY -> {
                        if (applyOne(userId, orgId, jobId, correlationId)) autoApplied++;
                        else humanReview++;
                    }
                    case HUMAN_REVIEW -> humanReview++;
                    case SAVE -> saved++;
                    case IGNORE -> ignored++;
                }
            } catch (Exception e) {
                failed++;
                metrics.recordFailure();
                deadLetters.record(correlationId, WORKFLOW_TYPE, "RUN", "job=" + jobId, e);
                log.warn("AUTOPILOT run error user={} job={}: {}", userId, jobId, e.toString());
            }
        }
        log.info("AUTOPILOT run user={} processed={} autoApplied={} humanReview={} saved={} ignored={} failed={}",
                userId, processed, autoApplied, humanReview, saved, ignored, failed);
        return new AutopilotRunSummary(processed, autoApplied, humanReview, saved, ignored, failed);
    }

    /**
     * Ensure a tailored resume, attempt the application, and — only on a genuine submission — start
     * Phase 3A tracking. Returns true when the application was actually submitted; false when it was
     * routed to human review (the fail-safe default) or auto-apply is disabled.
     */
    private boolean applyOne(UUID userId, UUID orgId, UUID jobId, UUID correlationId) {
        tailoringTrigger.triggerIfNeeded(userId, orgId, jobId); // reuses the 2D pipeline; async, no-op when off
        ApplicationSubmission submission = autoApplyEngine.apply(userId, jobId, null).orElse(null);
        if (submission == null) {
            correlation.advance(correlationId, "AUTO_APPLY", "DISABLED");
            return false;
        }
        correlation.advance(correlationId, "AUTO_APPLY", submission.getStatus());
        if (SubmissionStatus.SUBMITTED.name().equals(submission.getStatus())) {
            trackingEntry.seed(userId, jobId, submission.getId(), null, null, "autopilot");
            metrics.recordAutoApplied();
            return true;
        }
        return false; // HUMAN_REVIEW / FAILED — not counted as an applied job
    }
}
