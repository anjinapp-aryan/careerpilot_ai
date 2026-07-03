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
}
