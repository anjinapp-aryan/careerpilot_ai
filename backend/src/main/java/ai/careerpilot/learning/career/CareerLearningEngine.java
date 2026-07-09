package ai.careerpilot.learning.career;

import ai.careerpilot.domain.CareerLearning;
import ai.careerpilot.domain.SuccessPattern;
import ai.careerpilot.repo.CareerLearningRepository;
import ai.careerpilot.repo.SuccessPatternRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 6.6 — learns the user's best companies/skills/industries/locations/salary bands, one
 * {@link CareerLearning} row per (dimension, key), directly from {@link SuccessPattern} rows
 * (score = success rate, sample size = applications). Only reads {@code career_intelligence}
 * (Phase 3A.6) elsewhere in this phase — never writes to it.
 */
@Component
public class CareerLearningEngine {

    /** {@link CareerLearning} only models these 5 dimensions — ROLE/RESUME have no career-ranking analog. */
    private static final Map<String, String> CAREER_DIMENSIONS = Map.of(
            SuccessPattern.DIM_COMPANY, CareerLearning.DIM_COMPANY,
            SuccessPattern.DIM_SKILL, CareerLearning.DIM_SKILL,
            SuccessPattern.DIM_INDUSTRY, CareerLearning.DIM_INDUSTRY,
            SuccessPattern.DIM_LOCATION, CareerLearning.DIM_LOCATION,
            SuccessPattern.DIM_SALARY, CareerLearning.DIM_SALARY);

    private final SuccessPatternRepository successPatterns;
    private final CareerLearningRepository careerLearning;
    private final boolean enabled;

    public CareerLearningEngine(SuccessPatternRepository successPatterns, CareerLearningRepository careerLearning,
                                @Value("${learning.adaptive-career.enabled:false}") boolean enabled) {
        this.successPatterns = successPatterns;
        this.careerLearning = careerLearning;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    @Transactional
    public void recompute(UUID userId) {
        if (!enabled) return;
        for (String successDim : CAREER_DIMENSIONS.keySet()) {
            String careerDim = CAREER_DIMENSIONS.get(successDim);
            List<SuccessPattern> patterns = successPatterns.findByUserIdAndDimensionOrderBySuccessRateDesc(userId, successDim);
            for (SuccessPattern sp : patterns) {
                CareerLearning row = careerLearning.findByUserIdAndDimensionAndDimensionKey(userId, careerDim, sp.getDimensionKey())
                        .orElseGet(() -> CareerLearning.builder().userId(userId).dimension(careerDim).dimensionKey(sp.getDimensionKey()).build());
                row.setScore(sp.getSuccessRate());
                row.setSampleSize(sp.getApplications());
                row.setComputedAt(Instant.now());
                careerLearning.save(row);
            }
        }
    }
}
