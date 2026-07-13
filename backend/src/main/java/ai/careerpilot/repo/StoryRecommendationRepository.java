package ai.careerpilot.repo;

import ai.careerpilot.domain.StoryRecommendation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface StoryRecommendationRepository extends JpaRepository<StoryRecommendation, UUID> {
    List<StoryRecommendation> findByUserIdOrderByCreatedAtDesc(UUID userId);
    long countByUserId(UUID userId);
}
