package ai.careerpilot.workflowregistry;

import ai.careerpilot.domain.WorkflowDefinition;
import ai.careerpilot.repo.WorkflowDefinitionRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Workflow Registry, Phase 4 — {@link WorkflowRegistryService}. Pure catalog bookkeeping. */
class WorkflowRegistryServiceTest {

    private final WorkflowDefinitionRepository repository = mock(WorkflowDefinitionRepository.class);
    private final WorkflowRegistryService service = new WorkflowRegistryService(repository);

    private static WorkflowDefinition definition(String workflowId) {
        return WorkflowDefinition.builder().workflowId(workflowId).name("n").version("v1")
                .workflowType("JOB_DISCOVERY").status("ACTIVE").build();
    }

    @Test
    void registerSavesANewDefinition() {
        when(repository.existsByWorkflowId("JOB_DISCOVERY_V1")).thenReturn(false);
        when(repository.save(any(WorkflowDefinition.class))).thenAnswer(inv -> inv.getArgument(0));

        WorkflowDefinition saved = service.register(definition("JOB_DISCOVERY_V1"));

        assertThat(saved.getWorkflowId()).isEqualTo("JOB_DISCOVERY_V1");
        verify(repository).save(any(WorkflowDefinition.class));
    }

    @Test
    void registerRejectsADuplicateWorkflowId() {
        when(repository.existsByWorkflowId("JOB_DISCOVERY_V1")).thenReturn(true);

        assertThatThrownBy(() -> service.register(definition("JOB_DISCOVERY_V1")))
                .isInstanceOf(IllegalStateException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void getThrowsWhenWorkflowIdIsNotRegistered() {
        when(repository.findByWorkflowId("UNKNOWN")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get("UNKNOWN")).isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    void listActiveDelegatesToRepository() {
        when(repository.findByStatusOrderByWorkflowIdAsc("ACTIVE")).thenReturn(List.of(definition("JOB_DISCOVERY_V1")));

        assertThat(service.listActive()).hasSize(1);
    }

    @Test
    void latestForTypeReturnsTheFirstActiveMatch() {
        WorkflowDefinition def = definition("JOB_DISCOVERY_V1");
        when(repository.findByWorkflowTypeAndStatusOrderByVersionDesc("JOB_DISCOVERY", "ACTIVE")).thenReturn(List.of(def));

        assertThat(service.latestForType("JOB_DISCOVERY")).contains(def);
    }

    @Test
    void latestForTypeIsEmptyRatherThanThrowingWhenNoneRegistered() {
        when(repository.findByWorkflowTypeAndStatusOrderByVersionDesc("UNKNOWN", "ACTIVE")).thenReturn(List.of());

        assertThat(service.latestForType("UNKNOWN")).isEmpty();
    }
}
