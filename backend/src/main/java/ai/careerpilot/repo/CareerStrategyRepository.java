package ai.careerpilot.repo;

import ai.careerpilot.domain.CareerStrategy;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CareerStrategyRepository extends JpaRepository<CareerStrategy, UUID> {
    Optional<CareerStrategy> findByUserId(UUID userId);
}
