package ai.careerpilot.repo;

import ai.careerpilot.domain.StoryUsage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface StoryUsageRepository extends JpaRepository<StoryUsage, UUID> {
    List<StoryUsage> findByUserIdOrderByUsedAtDesc(UUID userId);
    List<StoryUsage> findByStarStoryIdOrderByUsedAtDesc(UUID starStoryId);
    long countByUserId(UUID userId);
}
