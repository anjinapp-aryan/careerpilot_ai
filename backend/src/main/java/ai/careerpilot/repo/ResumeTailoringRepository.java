package ai.careerpilot.repo;

import ai.careerpilot.domain.ResumeTailoring;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ResumeTailoringRepository extends JpaRepository<ResumeTailoring, UUID> {

    /** Latest tailored version for a (user, job) pair — the "current" tailored resume. */
    Optional<ResumeTailoring> findFirstByUserIdAndJobIdOrderByTailoringVersionDesc(UUID userId, UUID jobId);

    /** All versions for a (user, job) pair, newest first — the per-job version history. */
    List<ResumeTailoring> findByUserIdAndJobIdOrderByTailoringVersionDesc(UUID userId, UUID jobId);

    /** This user's tailoring history across all jobs, newest first. */
    List<ResumeTailoring> findByUserIdOrderByCreatedAtDesc(UUID userId);

    /** Next tailoring_version to assign for a (user, job) pair. */
    long countByUserIdAndJobId(UUID userId, UUID jobId);

    /**
     * Which of these jobs have a tailored resume at all — presence only, in one query.
     *
     * <p>Deliberately projects {@code jobId} rather than returning entities: the card only needs a
     * boolean, and {@code tailored_resume_text} is a full resume per row.
     */
    @org.springframework.data.jpa.repository.Query(
            "select distinct t.jobId from ResumeTailoring t where t.userId = :userId and t.jobId in :jobIds")
    List<UUID> findTailoredJobIds(@org.springframework.data.repository.query.Param("userId") UUID userId,
                                  @org.springframework.data.repository.query.Param("jobIds") java.util.Collection<UUID> jobIds);
}
