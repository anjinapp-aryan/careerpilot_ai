package ai.careerpilot.repo;

import ai.careerpilot.domain.StarStory;
import ai.careerpilot.story.StoryType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StarStoryRepository extends JpaRepository<StarStory, UUID> {
    List<StarStory> findByUserIdOrderByUpdatedAtDesc(UUID userId);
    List<StarStory> findByUserIdAndStoryTypeOrderByUpdatedAtDesc(UUID userId, StoryType storyType);
    Optional<StarStory> findByIdAndUserId(UUID id, UUID userId);
    long countByUserId(UUID userId);
}
