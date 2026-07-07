package ai.careerpilot.repo;

import ai.careerpilot.domain.ResumeAtsAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ResumeAtsAnalysisRepository extends JpaRepository<ResumeAtsAnalysis, UUID> {

    /** Latest analysis for a (user, job) pair, across all tailoring versions. */
    Optional<ResumeAtsAnalysis> findFirstByUserIdAndJobIdOrderByCreatedAtDesc(UUID userId, UUID jobId);

    /** All analyses for a (user, job) pair, newest first. */
    List<ResumeAtsAnalysis> findByUserIdAndJobIdOrderByCreatedAtDesc(UUID userId, UUID jobId);

    /** Phase 6.5 — analyses produced by one specific tailoring run, for the resume learning engine. */
    List<ResumeAtsAnalysis> findByUserIdAndResumeTailoringId(UUID userId, UUID resumeTailoringId);
}
