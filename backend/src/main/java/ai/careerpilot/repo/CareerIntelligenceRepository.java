package ai.careerpilot.repo;

import ai.careerpilot.domain.CareerIntelligence;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CareerIntelligenceRepository extends JpaRepository<CareerIntelligence, UUID> {

    List<CareerIntelligence> findByUserIdOrderByComputedAtDesc(UUID userId);

    List<CareerIntelligence> findByUserIdAndDimensionOrderByComputedAtDesc(UUID userId, String dimension);
}
