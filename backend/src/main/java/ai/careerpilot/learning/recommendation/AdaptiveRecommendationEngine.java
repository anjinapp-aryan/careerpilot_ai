package ai.careerpilot.learning.recommendation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Phase 6.4 — read-only facade over the learned {@code recommendation_weight} table, gated by
 * {@code learning.adaptive-recommendation.enabled} (env {@code ADAPTIVE_RECOMMENDATION_ENABLED}).
 *
 * <p><b>Not wired into {@code JobScoring}/{@code JobMatchingService} in this pass.</b> Phase 6's
 * hard constraint is "DO NOT MODIFY ... Phase 3B Relevance Engine" and, more broadly, "everything
 * additive" — hooking a learned boost into the live, already-tested scoring pipeline is an
 * integration decision with real regression risk that belongs in its own reviewed change, not
 * bundled into this pass. This engine is fully built, tested, and ready: {@link #getBoost} returns
 * 0 whenever the flag is off, so simply calling it from {@code JobScoring} in a future change is a
 * pure, instantly-reversible integration step.
 */
@Component
public class AdaptiveRecommendationEngine {

    private final RecommendationWeightManager weightManager;
    private final boolean enabled;

    public AdaptiveRecommendationEngine(RecommendationWeightManager weightManager,
                                        @Value("${learning.adaptive-recommendation.enabled:false}") boolean enabled) {
        this.weightManager = weightManager;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** The learned boost/penalty for one (user, dimension, key), or 0 when disabled/unknown. */
    public int getBoost(UUID userId, String dimension, String key) {
        if (!enabled || key == null) return 0;
        return weightManager.boostFor(userId, dimension, key);
    }
}
