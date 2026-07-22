package ai.careerpilot.repo;

import ai.careerpilot.domain.ApplicationExecutionAuditEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ApplicationExecutionAuditRepository
        extends JpaRepository<ApplicationExecutionAuditEntry, UUID> {

    /** Retention (Phase 3B prep): purge execution-audit rows created before a cutoff. Flag-gated caller. */
    long deleteByCreatedAtBefore(Instant cutoff);

    /** Phase 7.16.4 — the Audit Trail + Explainability source for the Application Detail Workspace. */
    List<ApplicationExecutionAuditEntry> findByApplicationExecutionIdOrderByCreatedAtAsc(UUID applicationExecutionId);
}
