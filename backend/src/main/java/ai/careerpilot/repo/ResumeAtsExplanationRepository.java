package ai.careerpilot.repo;

import ai.careerpilot.domain.ResumeAtsExplanation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ResumeAtsExplanationRepository extends JpaRepository<ResumeAtsExplanation, UUID> {

    Optional<ResumeAtsExplanation> findFirstByUserIdAndJobIdOrderByCreatedAtDesc(UUID userId, UUID jobId);

    List<ResumeAtsExplanation> findByUserIdAndJobIdOrderByCreatedAtDesc(UUID userId, UUID jobId);
}
