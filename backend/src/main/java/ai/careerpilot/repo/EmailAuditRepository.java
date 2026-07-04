package ai.careerpilot.repo;

import ai.careerpilot.domain.EmailAudit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface EmailAuditRepository extends JpaRepository<EmailAudit, UUID> {
}
