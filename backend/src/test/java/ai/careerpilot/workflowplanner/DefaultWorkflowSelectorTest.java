package ai.careerpilot.workflowplanner;

import ai.careerpilot.domain.WorkflowDefinition;
import ai.careerpilot.workflowregistry.WorkflowRegistryService;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultWorkflowSelectorTest {

    private final WorkflowRegistryService registry = mock(WorkflowRegistryService.class);
    private final DefaultWorkflowSelector selector = new DefaultWorkflowSelector(registry);

    @Test
    void delegatesToRegistryUsingTheRegistryWorkflowTypeKey() {
        WorkflowDefinition def = WorkflowDefinition.builder().workflowId("RESUME_OPTIMIZATION_V1").version("v1").build();
        when(registry.latestForType("RESUME_OPTIMIZATION")).thenReturn(Optional.of(def));

        Optional<WorkflowDefinition> result = selector.select(WorkflowType.RESUME);

        assertThat(result).contains(def);
    }

    @Test
    void emptyWhenNoDefinitionRegistered() {
        when(registry.latestForType("PORTFOLIO")).thenReturn(Optional.empty());

        assertThat(selector.select(WorkflowType.PORTFOLIO)).isEmpty();
    }
}
