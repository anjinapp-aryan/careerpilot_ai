package ai.careerpilot.repo;

import ai.careerpilot.domain.ApplicationAnalytics;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ApplicationAnalyticsRepository extends JpaRepository<ApplicationAnalytics, UUID> {

    List<ApplicationAnalytics> findByUserIdOrderByComputedAtDesc(UUID userId);

    List<ApplicationAnalytics> findByUserIdAndMetricOrderByComputedAtDesc(UUID userId, String metric);
}
