package ai.careerpilot.runtime;

import ai.careerpilot.agent.AgentServiceClient;
import ai.careerpilot.workflowregistry.WorkflowRegistryService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@link WorkflowRuntimeConfiguration} — the master {@code runtime.enabled} flag must gate every
 * bean in this package. {@link AgentServiceClient}/{@link WorkflowRegistryService} are supplied as
 * plain test doubles here (they're pre-existing, unconditionally-constructed beans in the real
 * application — this test only needs something the runtime's beans can wire against).
 */
class WorkflowRuntimeConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(WorkflowRuntimeConfiguration.class)
            .withBean(AgentServiceClient.class, () -> new AgentServiceClient("http://localhost:1", 100))
            .withBean(WorkflowRegistryService.class, () -> mock(WorkflowRegistryService.class));

    @Test
    void withFlagAtDefault_noRuntimeBeansAreConstructed() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(WorkflowRuntime.class);
            assertThat(context).doesNotHaveBean(WorkflowExecutor.class);
            assertThat(context).doesNotHaveBean(ExecutionRequestValidator.class);
            assertThat(context).doesNotHaveBean(WorkflowRegistryAdapter.class);
            assertThat(context).doesNotHaveBean(WorkflowContextFactory.class);
            assertThat(context).doesNotHaveBean(WorkflowStateFactory.class);
            assertThat(context).doesNotHaveBean(WorkflowLifecycleManager.class);
            assertThat(context).doesNotHaveBean(WorkflowResultMapper.class);
            assertThat(context).doesNotHaveBean(WorkflowMetrics.class);
        });
    }

    @Test
    void withFlagOn_allRuntimeBeansAreConstructed() {
        contextRunner.withPropertyValues("runtime.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(WorkflowRuntime.class);
            assertThat(context.getBean(WorkflowExecutor.class)).isInstanceOf(LangGraphWorkflowExecutor.class);
            assertThat(context.getBean(WorkflowMetrics.class)).isInstanceOf(InMemoryWorkflowMetrics.class);
        });
    }
}
