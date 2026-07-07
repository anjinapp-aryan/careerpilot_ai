package ai.careerpilot.learning.recommendation;

import ai.careerpilot.domain.RecommendationWeight;
import ai.careerpilot.repo.RecommendationWeightRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Phase 6.4 — persistence for learned recommendation boosts/penalties. Pure upsert, no business logic. */
@Component
public class RecommendationWeightManager {

    private final RecommendationWeightRepository weights;

    public RecommendationWeightManager(RecommendationWeightRepository weights) {
        this.weights = weights;
    }

    @Transactional
    public void upsert(UUID userId, String dimension, String key, int boost, String reason) {
        RecommendationWeight row = weights.findByUserIdAndDimensionAndDimensionKey(userId, dimension, key)
                .orElseGet(() -> RecommendationWeight.builder().userId(userId).dimension(dimension).dimensionKey(key).build());
        row.setBoost(boost);
        row.setReason(reason);
        row.setComputedAt(Instant.now());
        weights.save(row);
    }

    public List<RecommendationWeight> findByUser(UUID userId) {
        return weights.findByUserId(userId);
    }

    public int boostFor(UUID userId, String dimension, String key) {
        return weights.findByUserIdAndDimensionAndDimensionKey(userId, dimension, key)
                .map(RecommendationWeight::getBoost).orElse(0);
    }
}
