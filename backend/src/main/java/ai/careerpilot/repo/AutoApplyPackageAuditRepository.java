package ai.careerpilot.repo;

import ai.careerpilot.domain.AutoApplyPackageAuditEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AutoApplyPackageAuditRepository extends JpaRepository<AutoApplyPackageAuditEntry, UUID> {

    List<AutoApplyPackageAuditEntry> findByUserIdOrderByCreatedAtDesc(UUID userId);
}
