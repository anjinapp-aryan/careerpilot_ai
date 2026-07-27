package ai.careerpilot.planner.execution;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class MultiCapabilityExecutionConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(MultiCapabilityExecutionConfig.class);

    @Test
    void withFlagAtDefault_noExecutionBeansAreConstructed() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(MultiCapabilityMetrics.class);
            assertThat(context).doesNotHaveBean(CapabilityExecutor.class);
            assertThat(context).doesNotHaveBean(ParallelCapabilityExecutor.class);
            assertThat(context).doesNotHaveBean(ResultMerger.class);
            assertThat(context).doesNotHaveBean(ExecutionCoordinator.class);
        });
    }

    @Test
    void withFlagOn_allBeansConstructed() {
        contextRunner.withPropertyValues("multi.capability.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(MultiCapabilityMetrics.class);
            assertThat(context).hasSingleBean(CapabilityExecutor.class);
            assertThat(context).hasSingleBean(ParallelCapabilityExecutor.class);
            assertThat(context).hasSingleBean(ResultMerger.class);
            assertThat(context).hasSingleBean(ExecutionCoordinator.class);
        });
    }

    @Test
    void withFlagOnButMcpAndCapabilityLayersAbsent_executorDegradesGracefully() {
        contextRunner.withPropertyValues("multi.capability.enabled=true").run(context -> {
            ExecutionCoordinator coordinator = context.getBean(ExecutionCoordinator.class);
            assertThat(coordinator).isNotNull();
            // No MCP/Capability beans exist in this minimal context — a real plan execution
            // would degrade to failed-but-non-throwing results, verified at the unit level in
            // DefaultCapabilityExecutorTest; here we only confirm the bean graph itself is sound.
        });
    }
}
