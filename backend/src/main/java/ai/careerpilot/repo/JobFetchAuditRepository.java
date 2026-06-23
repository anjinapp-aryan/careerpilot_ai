package ai.careerpilot.repo;

import ai.careerpilot.domain.JobFetchAudit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface JobFetchAuditRepository extends JpaRepository<JobFetchAudit, UUID> {

    List<JobFetchAudit> findTop20ByOrderByStartedAtDesc();
}
