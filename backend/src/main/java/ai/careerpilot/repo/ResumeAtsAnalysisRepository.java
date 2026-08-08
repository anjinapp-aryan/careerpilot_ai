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

    /** Row shape for {@link #findLatestScoresByJob}: the newest analysis's score for one job. */
    interface LatestAtsScore {
        UUID getJobId();
        Integer getAtsScore();
    }

    /**
     * Bulk form of {@link #findFirstByUserIdAndJobIdOrderByCreatedAtDesc} — the newest analysis per
     * job, for a whole page of cards, in one query. Projects only the two columns the card reads
     * (presence + score), never the keyword/suggestion text blobs.
     */
    @org.springframework.data.jpa.repository.Query(value = """
            SELECT DISTINCT ON (job_id) job_id AS jobId, ats_score AS atsScore
            FROM resume_ats_analysis
            WHERE user_id = :userId AND job_id IN (:jobIds)
            ORDER BY job_id, created_at DESC
            """, nativeQuery = true)
    List<LatestAtsScore> findLatestScoresByJob(@org.springframework.data.repository.query.Param("userId") UUID userId,
                                               @org.springframework.data.repository.query.Param("jobIds") java.util.Collection<UUID> jobIds);
}
