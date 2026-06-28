package ai.careerpilot.repo;

import ai.careerpilot.domain.JobAiEnrichment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface JobAiEnrichmentRepository extends JpaRepository<JobAiEnrichment, UUID> {

    /** Upsert lookup — one enrichment row per job. */
    Optional<JobAiEnrichment> findByJobId(UUID jobId);
}
