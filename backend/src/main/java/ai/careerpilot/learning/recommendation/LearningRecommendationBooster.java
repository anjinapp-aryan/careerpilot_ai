package ai.careerpilot.learning.recommendation;

import ai.careerpilot.domain.CareerStrategy;
import ai.careerpilot.domain.Job;
import ai.careerpilot.domain.RecommendationWeight;
import ai.careerpilot.domain.ResumeLearning;
import ai.careerpilot.learning.resume.AdaptiveResumeEngine;
import ai.careerpilot.repo.CareerStrategyRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Phase 6.5 integration — the additive learning boost applied on top of {@code JobScoring}'s
 * deterministic match score. Each source is independently flag-gated and reads only already-computed
 * Phase 6 tables; when every flag is off this returns 0 for every job, so
 * {@code JobMatchingService}'s ranking/gating is byte-for-byte unchanged from before Phase 6.5.
 *
 * <p>Term mapping back to the spec: {@link AdaptiveRecommendationEngine#getBoost} already nets
 * "Historical Success Boost" (derived from interview/offer rates that feed {@code success_pattern})
 * against "Historical Failure Penalty" per dimension — there is no separately-stored interview-only
 * or offer-only boost, so those two terms are represented by the same netted per-dimension value.
 * {@link #resumeBoost} is the "Resume Success Boost" (best resume version's offer rate) and
 * {@link #careerBoost} is the "Career Success Boost" (learned career success probability).
 */
@Component
public class LearningRecommendationBooster {

    private static final int RESUME_BOOST_SCALE = 20;
    private static final int CAREER_BOOST_SCALE = 15;

    private final AdaptiveRecommendationEngine recommendationEngine;
    private final AdaptiveResumeEngine resumeEngine;
    private final CareerStrategyRepository careerStrategies;
    private final boolean careerBoostEnabled;

    public LearningRecommendationBooster(AdaptiveRecommendationEngine recommendationEngine,
                                         AdaptiveResumeEngine resumeEngine,
                                         CareerStrategyRepository careerStrategies,
                                         @Value("${learning.adaptive-career.enabled:false}") boolean careerBoostEnabled) {
        this.recommendationEngine = recommendationEngine;
        this.resumeEngine = resumeEngine;
        this.careerStrategies = careerStrategies;
        this.careerBoostEnabled = careerBoostEnabled;
    }

    /** True when at least one learning source is active — lets callers skip work entirely when dark. */
    public boolean isActive() {
        return recommendationEngine.isEnabled() || resumeEngine.isEnabled() || careerBoostEnabled;
    }

    /**
     * Total learning boost/penalty for one (user, job), combining dimension weights + resume + career
     * learning. Never throws; any missing signal simply contributes 0.
     */
    public int computeBoost(UUID userId, Job job, List<String> matchedSkills) {
        if (!isActive()) return 0;
        int boost = 0;
        if (recommendationEngine.isEnabled()) {
            boost += recommendationEngine.getBoost(userId, RecommendationWeight.DIM_COMPANY, job.getCompany());
            boost += recommendationEngine.getBoost(userId, RecommendationWeight.DIM_ROLE, job.getJobFamily());
            boost += recommendationEngine.getBoost(userId, RecommendationWeight.DIM_INDUSTRY, job.getJobFamily());
            boost += recommendationEngine.getBoost(userId, RecommendationWeight.DIM_LOCATION, job.getCountry());
            boost += recommendationEngine.getBoost(userId, RecommendationWeight.DIM_SALARY, salaryBand(job));
            if (matchedSkills != null) {
                for (String skill : matchedSkills) {
                    boost += recommendationEngine.getBoost(userId, RecommendationWeight.DIM_SKILL, skill);
                }
            }
        }
        boost += resumeBoost(userId);
        boost += careerBoost(userId);
        return boost;
    }

    private int resumeBoost(UUID userId) {
        if (!resumeEngine.isEnabled()) return 0;
        return resumeEngine.bestVersion(userId).map(this::resumeBoostFor).orElse(0);
    }

    private int resumeBoostFor(ResumeLearning rl) {
        BigDecimal rate = rl.getOfferRate();
        return rate == null ? 0 : Math.round(rate.floatValue() * RESUME_BOOST_SCALE);
    }

    private int careerBoost(UUID userId) {
        if (!careerBoostEnabled) return 0;
        return careerStrategies.findByUserId(userId).map(this::careerBoostFor).orElse(0);
    }

    private int careerBoostFor(CareerStrategy strategy) {
        BigDecimal probability = strategy.getCareerSuccessProbability();
        return probability == null ? 0 : Math.round(probability.floatValue() * CAREER_BOOST_SCALE);
    }

    /** Same 5-bucket salary banding as {@code LearningEventService.salaryBand}, kept in sync deliberately. */
    private static String salaryBand(Job job) {
        if (job.getSalaryMin() == null && job.getSalaryMax() == null) return null;
        double mid = (nz(job.getSalaryMin()) + nz(job.getSalaryMax())) / 2.0;
        if (mid <= 0) return null;
        if (mid < 60_000) return "UNDER_60K";
        if (mid < 100_000) return "60K_100K";
        if (mid < 150_000) return "100K_150K";
        if (mid < 200_000) return "150K_200K";
        return "OVER_200K";
    }

    private static double nz(BigDecimal v) {
        return v == null ? 0 : v.doubleValue();
    }
}
