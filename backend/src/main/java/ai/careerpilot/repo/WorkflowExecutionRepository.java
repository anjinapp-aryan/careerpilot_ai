package ai.careerpilot.repo;

import ai.careerpilot.domain.WorkflowExecution;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface WorkflowExecutionRepository extends JpaRepository<WorkflowExecution, UUID> {

    List<WorkflowExecution> findByUserIdOrderByCreatedAtDesc(UUID userId);

    List<WorkflowExecution> findByMissionIdOrderByCreatedAtDesc(UUID missionId);
}
