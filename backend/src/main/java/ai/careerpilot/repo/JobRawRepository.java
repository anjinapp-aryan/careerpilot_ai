package ai.careerpilot.repo;

import ai.careerpilot.domain.JobRaw;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface JobRawRepository extends JpaRepository<JobRaw, UUID> {

    /** Idempotency check: an identical payload (same checksum) for this listing is already captured. */
    boolean existsByProviderAndExternalIdAndChecksum(String provider, String externalId, String checksum);

    Optional<JobRaw> findFirstByProviderAndExternalIdOrderByFetchedAtDesc(String provider, String externalId);

    long countByRunId(UUID runId);
}
