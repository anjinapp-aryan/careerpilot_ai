package ai.careerpilot.repo;

import ai.careerpilot.domain.StoryVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StoryVersionRepository extends JpaRepository<StoryVersion, UUID> {
    List<StoryVersion> findByStarStoryIdOrderByVersionDesc(UUID starStoryId);
    Optional<StoryVersion> findByStarStoryIdAndVersion(UUID starStoryId, Integer version);
    long countByUserId(UUID userId);
}
