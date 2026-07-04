package ai.careerpilot.repo;

import ai.careerpilot.domain.ResumeTailoringAuditEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ResumeTailoringAuditRepository extends JpaRepository<ResumeTailoringAuditEntry, UUID> {

    List<ResumeTailoringAuditEntry> findByUserIdOrderByCreatedAtDesc(UUID userId);

    /** Retention (Phase 3B prep): purge tailoring-audit rows created before a cutoff. Flag-gated caller. */
    long deleteByCreatedAtBefore(Instant cutoff);
}
