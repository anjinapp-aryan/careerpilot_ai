package ai.careerpilot.repo;

import ai.careerpilot.domain.ApplicationLifecycleAudit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ApplicationLifecycleAuditRepository extends JpaRepository<ApplicationLifecycleAudit, UUID> {

    List<ApplicationLifecycleAudit> findByLifecycleIdOrderByCreatedAtDesc(UUID lifecycleId);
}
