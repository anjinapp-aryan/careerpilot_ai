package ai.careerpilot.repo;

import ai.careerpilot.domain.CareerLearning;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CareerLearningRepository extends JpaRepository<CareerLearning, UUID> {
    Optional<CareerLearning> findByUserIdAndDimensionAndDimensionKey(UUID userId, String dimension, String dimensionKey);
    List<CareerLearning> findByUserIdAndDimensionOrderByScoreDesc(UUID userId, String dimension);
    List<CareerLearning> findByUserIdOrderByScoreDesc(UUID userId);
}
