package ai.careerpilot.repo;

import ai.careerpilot.domain.SuccessPattern;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SuccessPatternRepository extends JpaRepository<SuccessPattern, UUID> {
    Optional<SuccessPattern> findByUserIdAndDimensionAndDimensionKey(UUID userId, String dimension, String dimensionKey);
    List<SuccessPattern> findByUserIdAndDimensionOrderBySuccessRateDesc(UUID userId, String dimension);
    List<SuccessPattern> findByUserIdOrderBySuccessRateDesc(UUID userId);
}
