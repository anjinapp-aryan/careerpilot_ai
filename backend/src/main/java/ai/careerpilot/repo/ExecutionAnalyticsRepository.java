package ai.careerpilot.repo;

import ai.careerpilot.domain.ExecutionAnalytics;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ExecutionAnalyticsRepository extends JpaRepository<ExecutionAnalytics, UUID> {

    List<ExecutionAnalytics> findByUserIdOrderByComputedAtDesc(UUID userId);
}
