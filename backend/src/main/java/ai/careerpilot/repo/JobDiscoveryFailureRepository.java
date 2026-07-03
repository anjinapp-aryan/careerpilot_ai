package ai.careerpilot.repo;

import ai.careerpilot.domain.JobDiscoveryFailure;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface JobDiscoveryFailureRepository extends JpaRepository<JobDiscoveryFailure, UUID> {

    List<JobDiscoveryFailure> findByRunIdOrderByCreatedAtDesc(UUID runId);
}
