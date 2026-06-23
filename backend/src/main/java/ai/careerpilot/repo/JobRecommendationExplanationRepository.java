package ai.careerpilot.repo;

import ai.careerpilot.domain.JobRecommendationExplanation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface JobRecommendationExplanationRepository
        extends JpaRepository<JobRecommendationExplanation, UUID> {

    Optional<JobRecommendationExplanation> findByUserIdAndJobId(UUID userId, UUID jobId);
}
