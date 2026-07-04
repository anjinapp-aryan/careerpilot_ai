package ai.careerpilot.repo;

import ai.careerpilot.domain.ResumeGapAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ResumeGapAnalysisRepository extends JpaRepository<ResumeGapAnalysis, UUID> {

    Optional<ResumeGapAnalysis> findFirstByUserIdAndJobIdOrderByCreatedAtDesc(UUID userId, UUID jobId);

    List<ResumeGapAnalysis> findByUserIdAndJobIdOrderByCreatedAtDesc(UUID userId, UUID jobId);
}
