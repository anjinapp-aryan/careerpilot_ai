package ai.careerpilot.repo;

import ai.careerpilot.domain.WorkflowDecisionLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface WorkflowDecisionLogRepository extends JpaRepository<WorkflowDecisionLog, UUID> {

    List<WorkflowDecisionLog> findByMissionExecutionIdOrderByCreatedAtAsc(UUID missionExecutionId);
}
