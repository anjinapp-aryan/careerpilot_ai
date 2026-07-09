package ai.careerpilot.learning.career;

import ai.careerpilot.domain.CareerLearning;
import ai.careerpilot.domain.CareerStrategy;
import ai.careerpilot.repo.CareerLearningRepository;
import ai.careerpilot.repo.CareerStrategyRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 6.5 integration — read-only facade over the persisted Phase 6.6 outputs
 * ({@link CareerStrategy}, {@link CareerLearning}), gated by {@code learning.adaptive-career.enabled}
 * (env {@code ADAPTIVE_CAREER_ENABLED}). This is what {@code WorkflowController}'s
 * {@code GET /api/workflow/career-learning} calls — it is additive alongside the existing
 * {@code GET /api/workflow/career-intelligence} (Phase 3A's separate, deterministic engine), which is
 * left untouched.
 */
@Component
public class CareerLearningFacade {

    private final CareerStrategyRepository strategies;
    private final CareerLearningRepository learning;
    private final boolean enabled;

    public CareerLearningFacade(CareerStrategyRepository strategies, CareerLearningRepository learning,
                                @Value("${learning.adaptive-career.enabled:false}") boolean enabled) {
        this.strategies = strategies;
        this.learning = learning;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Optional<CareerStrategy> strategy(UUID userId) {
        if (!enabled) return Optional.empty();
        return strategies.findByUserId(userId);
    }

    /** Top-ranked learned rows for one dimension (e.g. best companies), or empty when disabled. */
    public List<CareerLearning> top(UUID userId, String dimension) {
        if (!enabled) return List.of();
        return learning.findByUserIdAndDimensionOrderByScoreDesc(userId, dimension);
    }
}
