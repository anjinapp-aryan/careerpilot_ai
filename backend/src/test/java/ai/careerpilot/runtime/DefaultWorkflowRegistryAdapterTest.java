package ai.careerpilot.runtime;

import ai.careerpilot.domain.WorkflowDefinition;
import ai.careerpilot.workflowplanner.WorkflowType;
import ai.careerpilot.workflowregistry.WorkflowRegistryService;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultWorkflowRegistryAdapterTest {

    private final WorkflowRegistryService registryService = mock(WorkflowRegistryService.class);
    private final DefaultWorkflowRegistryAdapter adapter = new DefaultWorkflowRegistryAdapter(registryService);

    @Test
    void resolvesAnExistingDefinition() {
        WorkflowDefinition definition = WorkflowDefinition.builder()
                .workflowId("RESUME_OPTIMIZATION_V1").name("Resume Optimization").version("v1")
                .workflowType("RESUME_OPTIMIZATION").status("ACTIVE").build();
        when(registryService.latestForType("RESUME_OPTIMIZATION")).thenReturn(Optional.of(definition));

        ResolvedWorkflowDefinition resolved = adapter.resolve(WorkflowType.RESUME);

        assertThat(resolved.workflowId()).isEqualTo("RESUME_OPTIMIZATION_V1");
        assertThat(resolved.version()).isEqualTo("v1");
        assertThat(resolved.status()).isEqualTo("ACTIVE");
    }

    @Test
    void throwsWorkflowNotFoundWhenNoDefinitionExists() {
        when(registryService.latestForType("VISA")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adapter.resolve(WorkflowType.VISA))
                .isInstanceOf(WorkflowNotFoundException.class);
    }
}
