package ai.careerpilot.repo;

import ai.careerpilot.domain.JobRecommendation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JobRecommendationRepository extends JpaRepository<JobRecommendation, UUID> {

    List<JobRecommendation> findByUserIdOrderByMatchScoreDesc(UUID userId);

    Optional<JobRecommendation> findByUserIdAndJobId(UUID userId, UUID jobId);
}
