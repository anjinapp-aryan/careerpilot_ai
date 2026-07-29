package ai.careerpilot.api;

import ai.careerpilot.api.dto.WorkflowRegistryDtos.ExecuteWorkflowRequest;
import ai.careerpilot.api.dto.WorkflowRegistryDtos.WorkflowDefinitionRequest;
import ai.careerpilot.api.dto.WorkflowRegistryDtos.WorkflowDefinitionResponse;
import ai.careerpilot.api.dto.WorkflowRegistryDtos.WorkflowExecutionResponse;
import ai.careerpilot.domain.WorkflowDefinition;
import ai.careerpilot.domain.WorkflowExecution;
import ai.careerpilot.security.AuthenticatedUser;
import ai.careerpilot.workflowregistry.WorkflowExecutionService;
import ai.careerpilot.workflowregistry.WorkflowRegistryService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Workflow Registry, Phase 4 — {@link WorkflowRegistryController}. */
class WorkflowRegistryControllerTest {

    private final WorkflowRegistryService registry = mock(WorkflowRegistryService.class);
    private final WorkflowExecutionService executions = mock(WorkflowExecutionService.class);
    private final WorkflowRegistryController controller = new WorkflowRegistryController(registry, executions);
    private final AuthenticatedUser user = new AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID(), "a@b.com", "OWNER");

    @Test
    void listReturnsActiveDefinitions() {
        when(registry.listActive()).thenReturn(List.of(
                WorkflowDefinition.builder().id(UUID.randomUUID()).workflowId("JOB_DISCOVERY_V1")
                        .name("Job Discovery").version("v1").workflowType("JOB_DISCOVERY").status("ACTIVE").build()));

        List<WorkflowDefinitionResponse> result = controller.list(user);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).workflowId()).isEqualTo("JOB_DISCOVERY_V1");
    }

    @Test
    void registerDelegatesToRegistryService() {
        WorkflowDefinitionRequest request = new WorkflowDefinitionRequest(
                "SKILL_ANALYSIS_V1", "Skill Analysis", "desc", "v1", "SKILL_ANALYSIS", null, List.of("LEARNING_HELP"), null);
        when(registry.register(any(WorkflowDefinition.class))).thenAnswer(inv -> {
            WorkflowDefinition d = inv.getArgument(0);
            d.setId(UUID.randomUUID());
            return d;
        });

        WorkflowDefinitionResponse response = controller.register(user, request);

        assertThat(response.workflowId()).isEqualTo("SKILL_ANALYSIS_V1");
        assertThat(response.requiredCapabilities()).containsExactly("LEARNING_HELP");
    }

    @Test
    void executeLooksUpTheDefinitionByWorkflowIdAndDelegatesToExecutionService() {
        WorkflowDefinition definition = WorkflowDefinition.builder()
                .id(UUID.randomUUID()).workflowId("JOB_DISCOVERY_V1").workflowType("JOB_DISCOVERY").build();
        UUID missionId = UUID.randomUUID();
        when(registry.get("JOB_DISCOVERY_V1")).thenReturn(definition);
        when(executions.execute(definition, user.userId(), missionId)).thenReturn(
                WorkflowExecution.builder().id(UUID.randomUUID()).workflowDefinitionId(definition.getId())
                        .userId(user.userId()).missionId(missionId).status("DEFERRED").build());

        WorkflowExecutionResponse response = controller.execute(user, "JOB_DISCOVERY_V1", new ExecuteWorkflowRequest(missionId));

        assertThat(response.status()).isEqualTo("DEFERRED");
        assertThat(response.workflowId()).isEqualTo("JOB_DISCOVERY_V1");
        verify(executions).execute(definition, user.userId(), missionId);
    }
}
