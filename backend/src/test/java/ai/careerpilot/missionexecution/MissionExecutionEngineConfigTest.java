package ai.careerpilot.missionexecution;

import ai.careerpilot.capability.CapabilityType;
import ai.careerpilot.workflowplanner.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link MissionExecutionEngineConfig} — the master {@code execution.engine.enabled} flag must
 * gate every bean here. With it on, the fully-wired {@link MissionExecutionEngine} produces a
 * real plan end-to-end — no database or external dependency needed since this package reads
 * everything from the caller-supplied {@link ExecutionContext}.
 */
class MissionExecutionEngineConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(MissionExecutionEngineConfig.class);

    @Test
    void withFlagAtDefault_noEngineBeansAreConstructed() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(MissionExecutionEngine.class);
            assertThat(context).doesNotHaveBean(ExecutionPriorityResolver.class);
            assertThat(context).doesNotHaveBean(ExecutionDependencyResolver.class);
            assertThat(context).doesNotHaveBean(ExecutionScheduler.class);
            assertThat(context).doesNotHaveBean(ExecutionEstimator.class);
            assertThat(context).doesNotHaveBean(ExecutionValidator.class);
            assertThat(context).doesNotHaveBean(ExecutionHistory.class);
        });
    }

    @Test
    void withFlagOn_allBeansConstructed() {
        contextRunner.withPropertyValues("execution.engine.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(MissionExecutionEngine.class);
            assertThat(context.getBean(ExecutionHistory.class)).isInstanceOf(InMemoryExecutionHistory.class);
        });
    }

    @Test
    void endToEnd_realPlanIsProducedFromARealWorkflowPlan() {
        contextRunner.withPropertyValues("execution.engine.enabled=true").run(context -> {
            MissionExecutionEngine engine = context.getBean(MissionExecutionEngine.class);
            WorkflowEstimate estimate = new WorkflowEstimate(Duration.ofMinutes(5), 500, java.math.BigDecimal.ONE, 1, 1, WorkflowComplexity.LOW, 0.7);
            WorkflowPlan wp = new WorkflowPlan(UUID.randomUUID(), WorkflowType.RESUME, "v1", WorkflowPriority.HIGH,
                    UUID.randomUUID(), null, CapabilityType.RESUME_ANALYSIS, WorkflowComplexity.LOW, Duration.ofMinutes(5),
                    List.of(), List.of(), List.of(), List.of(), false, RetryStrategy.standard(),
                    FallbackStrategy.escalateToHuman(), List.of(), "RESUME_GRAPH_V1", "start", "end", "auto",
                    Map.of(), estimate, WorkflowExecutionStrategy.SEQUENTIAL, Instant.now());

            MissionExecutionPlan plan = engine.plan(new ExecutionContext(UUID.randomUUID(), List.of(wp)));

            assertThat(plan.decisions()).hasSize(1);
            assertThat(plan.decisions().get(0).policy()).isEqualTo(ExecutionPolicy.SEQUENTIAL);
            assertThat(plan.executionQueue().readyNow()).hasSize(1);
        });
    }
}
