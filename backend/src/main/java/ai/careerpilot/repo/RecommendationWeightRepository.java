package ai.careerpilot.repo;

import ai.careerpilot.domain.RecommendationWeight;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RecommendationWeightRepository extends JpaRepository<RecommendationWeight, UUID> {
    Optional<RecommendationWeight> findByUserIdAndDimensionAndDimensionKey(UUID userId, String dimension, String dimensionKey);
    List<RecommendationWeight> findByUserId(UUID userId);
}
