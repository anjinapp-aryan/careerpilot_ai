package ai.careerpilot.autopilot.decision;

import ai.careerpilot.domain.ApplicationDecision;
import ai.careerpilot.domain.JobRecommendation;
import ai.careerpilot.domain.ResumeAtsAnalysis;
import ai.careerpilot.jobdiscovery.JobCategory;
import ai.careerpilot.repo.ApplicationDecisionRepository;
import ai.careerpilot.repo.JobRecommendationRepository;
import ai.careerpilot.repo.ResumeAtsAnalysisRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Phase 7.1 — deterministic (no-LLM) decision on whether the autonomous agent should apply to a job.
 * Reads <em>already-computed</em> Phase 1–6 signals (never re-scores, never calls a provider):
 * the persisted {@link JobRecommendation} (match score, category, must-apply, priority, and the
 * Phase 6.5 learning boost baked into {@code score_breakdown}) and the latest {@link ResumeAtsAnalysis}.
 *
 * <p>The math lives in the pure, side-effect-free {@link #evaluate} so it is unit-testable without
 * mocks; {@link #decide} only gathers signals, persists one append-only {@link ApplicationDecision}
 * row, and returns it. Fails safe: any missing/uncertain signal caps the outcome at
 * {@code HUMAN_REVIEW}, and a net-negative learning history can never reach {@code AUTO_APPLY}.
 *
 * <p>Flag-gated by {@code application.decision.enabled} (default off): when disabled every entry
 * point is a no-op returning empty, so wiring it into the orchestrator changes nothing until enabled.
 */
@Service
public class ApplicationDecisionEngine {

    private static final Logger log = LoggerFactory.getLogger(ApplicationDecisionEngine.class);

    private final JobRecommendationRepository recommendations;
    private final ResumeAtsAnalysisRepository atsAnalyses;
    private final ApplicationDecisionRepository decisions;
    private final ObjectMapper mapper = new ObjectMapper();

    private final boolean enabled;
    private final int autoApplyMinScore;
    private final int reviewMinScore;
    private final int saveMinScore;
    private final int atsFloor;
    private final int failurePenaltyCap;

    public ApplicationDecisionEngine(JobRecommendationRepository recommendations,
                                     ResumeAtsAnalysisRepository atsAnalyses,
                                     ApplicationDecisionRepository decisions,
                                     @Value("${application.decision.enabled:false}") boolean enabled,
                                     @Value("${application.decision.auto-apply-min-score:90}") int autoApplyMinScore,
                                     @Value("${application.decision.review-min-score:75}") int reviewMinScore,
                                     @Value("${application.decision.save-min-score:60}") int saveMinScore,
                                     @Value("${application.decision.ats-floor:70}") int atsFloor,
                                     @Value("${application.decision.failure-penalty-cap:-20}") int failurePenaltyCap) {
        this.recommendations = recommendations;
        this.atsAnalyses = atsAnalyses;
        this.decisions = decisions;
        this.enabled = enabled;
        this.autoApplyMinScore = autoApplyMinScore;
        this.reviewMinScore = reviewMinScore;
        this.saveMinScore = saveMinScore;
        this.atsFloor = atsFloor;
        this.failurePenaltyCap = failurePenaltyCap;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** All decision inputs, so {@link #evaluate} stays a pure function of already-computed signals. */
    public record DecisionSignals(int matchScore, boolean mustApply, Integer atsScore,
                                  int learningBoost, String category, String priority) {}

    /** The outcome plus a human-readable justification (used for the audit row and the Copilot). */
    public record DecisionResult(DecisionOutcome outcome, String reason) {}

    /**
     * Pure decision rule. Deterministic and side-effect free.
     *
     * <ul>
     *   <li>{@code ARCHIVED} category or score below the save floor → {@code IGNORE}.</li>
     *   <li>{@code AUTO_APPLY} only when score ≥ auto-apply floor, must-apply is set, ATS (if known)
     *       clears its floor, and learning is not a net penalty — otherwise it degrades to review.</li>
     *   <li>A strong historical-failure penalty ({@code learningBoost ≤ failurePenaltyCap}) caps the
     *       outcome at {@code HUMAN_REVIEW}, never auto-apply.</li>
     *   <li>Score ≥ review floor → {@code HUMAN_REVIEW}; ≥ save floor → {@code SAVE}; else {@code IGNORE}.</li>
     * </ul>
     */
    public DecisionResult evaluate(DecisionSignals s) {
        if (JobCategory.ARCHIVED.name().equals(s.category())) {
            return new DecisionResult(DecisionOutcome.IGNORE, "Recommendation was archived.");
        }
        if (s.matchScore() < saveMinScore) {
            return new DecisionResult(DecisionOutcome.IGNORE,
                    "Match score " + s.matchScore() + " below the pipeline floor of " + saveMinScore + ".");
        }

        boolean strongFailureHistory = s.learningBoost() <= failurePenaltyCap;
        boolean atsOk = s.atsScore() == null || s.atsScore() >= atsFloor;

        if (s.matchScore() >= autoApplyMinScore && s.mustApply() && atsOk && !strongFailureHistory
                && s.learningBoost() >= 0) {
            return new DecisionResult(DecisionOutcome.AUTO_APPLY,
                    "Strong match (" + s.matchScore() + "), flagged must-apply"
                            + (s.atsScore() != null ? ", ATS " + s.atsScore() : "")
                            + (s.learningBoost() > 0 ? ", positive learning history (+" + s.learningBoost() + ")" : "")
                            + ".");
        }

        if (s.matchScore() >= reviewMinScore) {
            return new DecisionResult(DecisionOutcome.HUMAN_REVIEW, reviewReason(s, strongFailureHistory, atsOk));
        }
        return new DecisionResult(DecisionOutcome.SAVE,
                "Match score " + s.matchScore() + " — kept in the pipeline for later review.");
    }

    private static String reviewReason(DecisionSignals s, boolean strongFailureHistory, boolean atsOk) {
        if (strongFailureHistory) return "Promising match but historical failures at this target warrant a human check.";
        if (!atsOk) return "Good match but ATS score is below the auto-apply floor — human review recommended.";
        if (!s.mustApply()) return "Good match (" + s.matchScore() + ") but not flagged must-apply — human review.";
        return "Match score " + s.matchScore() + " qualifies for human review.";
    }

    /**
     * Compute and persist the decision for a (user, job). Empty when disabled or when no
     * recommendation exists yet (nothing to decide against — the discovery/matching pass must run first).
     */
    @Transactional
    public Optional<ApplicationDecision> decide(UUID userId, UUID jobId) {
        if (!enabled) return Optional.empty();
        JobRecommendation rec = recommendations.findByUserIdAndJobId(userId, jobId).orElse(null);
        if (rec == null) {
            log.debug("APP_DECISION no recommendation for user={} job={}", userId, jobId);
            return Optional.empty();
        }

        Integer atsScore = atsAnalyses.findFirstByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId)
                .map(ResumeAtsAnalysis::getAtsScore).orElse(null);
        int learningBoost = learningBoost(rec);

        DecisionSignals signals = new DecisionSignals(rec.getMatchScore(),
                Boolean.TRUE.equals(rec.getMustApply()), atsScore, learningBoost,
                rec.getCategory(), rec.getPriority());
        DecisionResult result = evaluate(signals);

        ApplicationDecision saved = decisions.save(ApplicationDecision.builder()
                .userId(userId).jobId(jobId)
                .outcome(result.outcome().name())
                .matchScore(rec.getMatchScore())
                .atsScore(atsScore)
                .learningBoost(learningBoost)
                .mustApply(Boolean.TRUE.equals(rec.getMustApply()))
                .priority(rec.getPriority())
                .reason(result.reason())
                .build());
        log.info("APP_DECISION user={} job={} outcome={} score={} ats={} learning={}",
                userId, jobId, result.outcome(), rec.getMatchScore(), atsScore, learningBoost);
        return Optional.of(saved);
    }

    public Optional<ApplicationDecision> latest(UUID userId, UUID jobId) {
        return decisions.findFirstByUserIdAndJobIdOrderByDecidedAtDesc(userId, jobId);
    }

    /** The Phase 6.5 learning boost already baked into the recommendation's score_breakdown JSON, or 0. */
    private int learningBoost(JobRecommendation rec) {
        if (rec.getScoreBreakdown() == null || rec.getScoreBreakdown().isBlank()) return 0;
        try {
            JsonNode node = mapper.readTree(rec.getScoreBreakdown());
            JsonNode lb = node.get("learningBoost");
            return lb == null ? 0 : lb.asInt(0);
        } catch (Exception e) {
            return 0;
        }
    }
}
