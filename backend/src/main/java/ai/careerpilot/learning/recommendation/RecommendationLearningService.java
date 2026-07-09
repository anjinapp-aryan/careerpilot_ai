package ai.careerpilot.learning.recommendation;

import ai.careerpilot.domain.FailurePattern;
import ai.careerpilot.domain.RecommendationWeight;
import ai.careerpilot.domain.SuccessPattern;
import ai.careerpilot.repo.FailurePatternRepository;
import ai.careerpilot.repo.SuccessPatternRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 6.4 — computes learned company/role/skill/industry/location/salary boosts from the latest
 * {@link SuccessPattern}/{@link FailurePattern} rows. Deterministic: {@code boost = round(successRate*30)
 * + failurePenalty} (failurePenalty is already negative). Purely additive: see
 * {@link AdaptiveRecommendationEngine} javadoc for why this is not yet wired into live scoring.
 */
@Service
public class RecommendationLearningService {

    private static final int SUCCESS_SCALE = 30;

    /** {@link RecommendationWeight} only models these 6 dimensions — RESUME has no recommendation-boost analog. */
    private static final Map<String, String> RECOMMENDATION_DIMENSIONS = Map.of(
            SuccessPattern.DIM_COMPANY, RecommendationWeight.DIM_COMPANY,
            SuccessPattern.DIM_ROLE, RecommendationWeight.DIM_ROLE,
            SuccessPattern.DIM_SKILL, RecommendationWeight.DIM_SKILL,
            SuccessPattern.DIM_INDUSTRY, RecommendationWeight.DIM_INDUSTRY,
            SuccessPattern.DIM_LOCATION, RecommendationWeight.DIM_LOCATION,
            SuccessPattern.DIM_SALARY, RecommendationWeight.DIM_SALARY);

    private final SuccessPatternRepository successPatterns;
    private final FailurePatternRepository failurePatterns;
    private final RecommendationWeightManager weightManager;

    public RecommendationLearningService(SuccessPatternRepository successPatterns,
                                         FailurePatternRepository failurePatterns,
                                         RecommendationWeightManager weightManager) {
        this.successPatterns = successPatterns;
        this.failurePatterns = failurePatterns;
        this.weightManager = weightManager;
    }

    /** Recompute every learned weight for a user from their current success/failure patterns. */
    public void recompute(UUID userId) {
        Map<String, FailurePattern> failuresByKey = new LinkedHashMap<>();
        for (String dimension : RECOMMENDATION_DIMENSIONS.keySet()) {
            for (FailurePattern fp : failurePatterns.findByUserIdAndDimensionOrderByFailureRateDesc(userId, dimension)) {
                failuresByKey.put(dimension + "::" + fp.getDimensionKey(), fp);
            }
        }

        for (String dimension : RECOMMENDATION_DIMENSIONS.keySet()) {
            String weightDimension = RECOMMENDATION_DIMENSIONS.get(dimension);
            for (SuccessPattern sp : successPatterns.findByUserIdAndDimensionOrderBySuccessRateDesc(userId, dimension)) {
                int successBoost = successBoost(sp.getSuccessRate());
                FailurePattern fp = failuresByKey.remove(dimension + "::" + sp.getDimensionKey());
                int penalty = fp != null && fp.getRecommendedPenalty() != null ? fp.getRecommendedPenalty() : 0;
                int boost = successBoost + penalty;
                String reason = "Learned from " + sp.getApplications() + " applications, "
                        + sp.getOffers() + " offers" + (fp != null ? "; " + fp.getApplications() + " applications with no response" : "");
                weightManager.upsert(userId, weightDimension, sp.getDimensionKey(), boost, reason);
            }
        }
        // Remaining failure-only keys (never a success) still get a pure penalty.
        for (Map.Entry<String, FailurePattern> entry : failuresByKey.entrySet()) {
            FailurePattern fp = entry.getValue();
            String weightDimension = RECOMMENDATION_DIMENSIONS.get(fp.getDimension());
            if (weightDimension == null || fp.getRecommendedPenalty() == null) continue;
            String reason = fp.getApplications() + " applications with no response";
            weightManager.upsert(userId, weightDimension, fp.getDimensionKey(), fp.getRecommendedPenalty(), reason);
        }
    }

    private static int successBoost(BigDecimal successRate) {
        if (successRate == null) return 0;
        return Math.round(successRate.floatValue() * SUCCESS_SCALE);
    }
}
