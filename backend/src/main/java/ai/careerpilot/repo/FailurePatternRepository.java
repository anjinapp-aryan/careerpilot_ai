package ai.careerpilot.repo;

import ai.careerpilot.domain.FailurePattern;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FailurePatternRepository extends JpaRepository<FailurePattern, UUID> {
    Optional<FailurePattern> findByUserIdAndDimensionAndDimensionKey(UUID userId, String dimension, String dimensionKey);
    List<FailurePattern> findByUserIdAndDimensionOrderByFailureRateDesc(UUID userId, String dimension);
    List<FailurePattern> findByUserIdOrderByFailureRateDesc(UUID userId);
}
