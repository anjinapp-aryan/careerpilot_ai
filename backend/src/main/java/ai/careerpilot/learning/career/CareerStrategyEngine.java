package ai.careerpilot.learning.career;

import ai.careerpilot.domain.CareerStrategy;
import ai.careerpilot.repo.CareerStrategyRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/** Phase 6.6 — orchestrates {@link CareerProbabilityEngine} + {@link CareerTrajectoryAnalyzer} into one persisted {@link CareerStrategy} row per user. */
@Component
public class CareerStrategyEngine {

    private final CareerProbabilityEngine probabilityEngine;
    private final CareerTrajectoryAnalyzer trajectoryAnalyzer;
    private final CareerStrategyRepository strategies;
    private final boolean enabled;

    public CareerStrategyEngine(CareerProbabilityEngine probabilityEngine, CareerTrajectoryAnalyzer trajectoryAnalyzer,
                                CareerStrategyRepository strategies,
                                @Value("${learning.adaptive-career.enabled:false}") boolean enabled) {
        this.probabilityEngine = probabilityEngine;
        this.trajectoryAnalyzer = trajectoryAnalyzer;
        this.strategies = strategies;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    @Transactional
    public void recompute(UUID userId) {
        if (!enabled) return;
        CareerProbabilityEngine.Probabilities p = probabilityEngine.compute(userId);
        String trajectory = trajectoryAnalyzer.recommend(userId);

        CareerStrategy row = strategies.findByUserId(userId)
                .orElseGet(() -> CareerStrategy.builder().userId(userId).build());
        row.setCareerSuccessProbability(p.careerSuccessProbability());
        row.setInterviewProbability(p.interviewProbability());
        row.setOfferProbability(p.offerProbability());
        row.setCareerGrowthProbability(p.careerGrowthProbability());
        row.setMarketDemandScore(p.marketDemandScore());
        row.setRecommendedTrajectory(trajectory);
        row.setComputedAt(Instant.now());
        strategies.save(row);
    }
}
