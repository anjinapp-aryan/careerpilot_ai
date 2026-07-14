package ai.careerpilot.repo;

import ai.careerpilot.domain.StoryAnalytics;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface StoryAnalyticsRepository extends JpaRepository<StoryAnalytics, UUID> {
    Optional<StoryAnalytics> findByUserId(UUID userId);
}
