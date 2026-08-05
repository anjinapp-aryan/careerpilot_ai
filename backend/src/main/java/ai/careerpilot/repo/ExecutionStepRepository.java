package ai.careerpilot.repo;

import ai.careerpilot.domain.ExecutionStep;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Phase F1 — per-step state for multi-page forms. */
public interface ExecutionStepRepository extends JpaRepository<ExecutionStep, UUID> {

    List<ExecutionStep> findByExecutionIdOrderByStepNumberAsc(UUID executionId);

    Optional<ExecutionStep> findByExecutionIdAndStepNumber(UUID executionId, int stepNumber);

    Optional<ExecutionStep> findByApprovalQueueEntryId(UUID approvalQueueEntryId);
}
