package ai.careerpilot.repo;

import ai.careerpilot.domain.JobDiscoveryAudit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface JobDiscoveryAuditRepository extends JpaRepository<JobDiscoveryAudit, UUID> {

    List<JobDiscoveryAudit> findTop50ByOrderByStartedAtDesc();

    List<JobDiscoveryAudit> findByRunIdOrderByStartedAtDesc(UUID runId);
}
