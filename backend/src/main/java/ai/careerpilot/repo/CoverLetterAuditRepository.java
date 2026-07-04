package ai.careerpilot.repo;

import ai.careerpilot.domain.CoverLetterAuditEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CoverLetterAuditRepository extends JpaRepository<CoverLetterAuditEntry, UUID> {

    List<CoverLetterAuditEntry> findByUserIdOrderByCreatedAtDesc(UUID userId);
}
