package ai.careerpilot.repo;

import ai.careerpilot.domain.ApprovalAuditEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ApprovalAuditRepository extends JpaRepository<ApprovalAuditEntry, UUID> {
}
