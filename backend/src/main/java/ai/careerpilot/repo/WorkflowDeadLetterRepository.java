package ai.careerpilot.repo;

import ai.careerpilot.domain.WorkflowDeadLetter;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface WorkflowDeadLetterRepository extends JpaRepository<WorkflowDeadLetter, UUID> {

    List<WorkflowDeadLetter> findByCorrelationIdOrderByCreatedAtDesc(UUID correlationId);

    long countByWorkflow(String workflow);

    /** Retention (Phase 3B prep): purge dead-letter rows captured before a cutoff. Flag-gated caller. */
    long deleteByCreatedAtBefore(Instant cutoff);
}
