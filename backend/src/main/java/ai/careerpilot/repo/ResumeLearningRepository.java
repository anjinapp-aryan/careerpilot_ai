package ai.careerpilot.repo;

import ai.careerpilot.domain.ResumeLearning;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ResumeLearningRepository extends JpaRepository<ResumeLearning, UUID> {
    Optional<ResumeLearning> findByUserIdAndResumeVersion(UUID userId, String resumeVersion);
    List<ResumeLearning> findByUserIdOrderByOfferRateDesc(UUID userId);
    Optional<ResumeLearning> findByUserIdAndBestVersionTrue(UUID userId);
}
