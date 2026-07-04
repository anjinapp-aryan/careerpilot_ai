package ai.careerpilot.repo;

import ai.careerpilot.domain.ApplicationExecutionAuditEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ApplicationExecutionAuditRepository
        extends JpaRepository<ApplicationExecutionAuditEntry, UUID> {
}
