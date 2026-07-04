package ai.careerpilot.repo;

import ai.careerpilot.domain.WorkflowCorrelation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface WorkflowCorrelationRepository extends JpaRepository<WorkflowCorrelation, UUID> {

    Optional<WorkflowCorrelation> findByCorrelationId(UUID correlationId);

    long countByStatus(String status);

    /** Retention (Phase 3B prep): purge only <em>terminal</em> correlations last updated before a cutoff —
     * never touches STARTED/IN_PROGRESS rows, so an in-flight workflow can't be reaped. Flag-gated caller. */
    long deleteByStatusInAndUpdatedAtBefore(Collection<String> statuses, Instant cutoff);
}
