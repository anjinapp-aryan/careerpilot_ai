package ai.careerpilot.repo;

import ai.careerpilot.domain.ApplicationPackageAuditEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ApplicationPackageAuditRepository extends JpaRepository<ApplicationPackageAuditEntry, UUID> {

    List<ApplicationPackageAuditEntry> findByUserIdOrderByCreatedAtDesc(UUID userId);
}
