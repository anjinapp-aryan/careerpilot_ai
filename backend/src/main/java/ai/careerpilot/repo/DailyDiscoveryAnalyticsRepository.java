package ai.careerpilot.repo;

import ai.careerpilot.domain.DailyDiscoveryAnalytics;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DailyDiscoveryAnalyticsRepository extends JpaRepository<DailyDiscoveryAnalytics, UUID> {
    Optional<DailyDiscoveryAnalytics> findByRunIdAndUserIdIsNull(UUID runId);
    List<DailyDiscoveryAnalytics> findByUserIdOrderByComputedAtDesc(UUID userId);
    Optional<DailyDiscoveryAnalytics> findFirstByUserIdOrderByComputedAtDesc(UUID userId);
}
