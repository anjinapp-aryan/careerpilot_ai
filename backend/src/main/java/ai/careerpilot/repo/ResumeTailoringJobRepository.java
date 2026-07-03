package ai.careerpilot.repo;

import ai.careerpilot.domain.ResumeTailoringJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ResumeTailoringJobRepository extends JpaRepository<ResumeTailoringJob, UUID> {

    /** Ownership-checked lookup for the polling endpoint. */
    Optional<ResumeTailoringJob> findByIdAndUserId(UUID id, UUID userId);

    long countByStatus(String status);

    long countByStatusAndCreatedAtAfter(String status, Instant since);

    /** Oldest still-queued job, for the queue-diagnostics "oldest queued age" figure. */
    Optional<ResumeTailoringJob> findFirstByStatusOrderByCreatedAtAsc(String status);

    /** Most recent N jobs (any status) for the health endpoint's rolling failure-rate check. */
    List<ResumeTailoringJob> findTop20ByOrderByCreatedAtDesc();
}
