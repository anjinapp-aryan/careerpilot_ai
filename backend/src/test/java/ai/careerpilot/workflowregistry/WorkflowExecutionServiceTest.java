package ai.careerpilot.workflowregistry;

import ai.careerpilot.domain.WorkflowDefinition;
import ai.careerpilot.domain.WorkflowExecution;
import ai.careerpilot.repo.WorkflowExecutionRepository;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Workflow Registry, Phase 4 — {@link WorkflowExecutionService}. Pins the deliberate
 * always-DEFERRED behavior (no workflow type is actually invoked from this registry today).
 */
class WorkflowExecutionServiceTest {

    private final WorkflowExecutionRepository repository = mock(WorkflowExecutionRepository.class);
    private final WorkflowExecutionService service = new WorkflowExecutionService(repository);

    @Test
    void executeAlwaysProducesADeferredExecutionRecord() {
        WorkflowDefinition definition = WorkflowDefinition.builder()
                .id(UUID.randomUUID()).workflowId("JOB_DISCOVERY_V1").workflowType("JOB_DISCOVERY").build();
        UUID userId = UUID.randomUUID();
        UUID missionId = UUID.randomUUID();
        when(repository.save(any(WorkflowExecution.class))).thenAnswer(inv -> inv.getArgument(0));

        WorkflowExecution execution = service.execute(definition, userId, missionId);

        assertThat(execution.getStatus()).isEqualTo("DEFERRED");
        assertThat(execution.getUserId()).isEqualTo(userId);
        assertThat(execution.getMissionId()).isEqualTo(missionId);
        assertThat(execution.getWorkflowDefinitionId()).isEqualTo(definition.getId());
        assertThat(execution.getNotes()).contains("JOB_DISCOVERY");
    }
}
