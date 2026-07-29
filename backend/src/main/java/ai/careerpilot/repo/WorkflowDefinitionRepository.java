package ai.careerpilot.repo;

import ai.careerpilot.domain.WorkflowDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkflowDefinitionRepository extends JpaRepository<WorkflowDefinition, UUID> {

    List<WorkflowDefinition> findByStatusOrderByWorkflowIdAsc(String status);

    Optional<WorkflowDefinition> findByWorkflowId(String workflowId);

    boolean existsByWorkflowId(String workflowId);

    List<WorkflowDefinition> findByWorkflowTypeAndStatusOrderByVersionDesc(String workflowType, String status);
}
