package ai.careerpilot.planner.execution;

import ai.careerpilot.capability.CapabilityType;
import ai.careerpilot.mcp.McpExecutionContext;
import ai.careerpilot.planner.CapabilityPriority;
import ai.careerpilot.planner.CapabilityStep;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultParallelCapabilityExecutorTest {

    private final InMemoryMultiCapabilityMetrics metrics = new InMemoryMultiCapabilityMetrics();

    private McpExecutionContext context() {
        return new McpExecutionContext(UUID.randomUUID(), null, null, "trace", Duration.ofSeconds(5), Map.of());
    }

    @Test
    void executesAllStepsInStageAndReturnsAllResults() {
        CapabilityExecutor executor = (step, ctx) -> new ExecutionResult(step.type(), Map.of(), true, 1, 5, null);
        DefaultParallelCapabilityExecutor parallel = new DefaultParallelCapabilityExecutor(executor, metrics);

        List<CapabilityStep> stage = List.of(
                new CapabilityStep(CapabilityType.RESUME_ANALYSIS, CapabilityPriority.HIGH),
                new CapabilityStep(CapabilityType.GITHUB_REVIEW, CapabilityPriority.HIGH));

        Map<CapabilityType, ExecutionResult> results = parallel.executeStage(stage, context());

        assertThat(results).hasSize(2);
        assertThat(results.get(CapabilityType.RESUME_ANALYSIS).success()).isTrue();
        assertThat(results.get(CapabilityType.GITHUB_REVIEW).success()).isTrue();
    }

    @Test
    void actuallyRunsStepsConcurrentlyNotSequentially() throws InterruptedException {
        CopyOnWriteArrayList<Long> startTimes = new CopyOnWriteArrayList<>();
        CapabilityExecutor slowExecutor = (step, ctx) -> {
            startTimes.add(System.currentTimeMillis());
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {
            }
            return new ExecutionResult(step.type(), Map.of(), true, 1, 100, null);
        };
        DefaultParallelCapabilityExecutor parallel = new DefaultParallelCapabilityExecutor(slowExecutor, metrics);
        List<CapabilityStep> stage = List.of(
                new CapabilityStep(CapabilityType.RESUME_ANALYSIS, CapabilityPriority.HIGH),
                new CapabilityStep(CapabilityType.GITHUB_REVIEW, CapabilityPriority.HIGH),
                new CapabilityStep(CapabilityType.LEARNING_HELP, CapabilityPriority.HIGH));

        long start = System.currentTimeMillis();
        parallel.executeStage(stage, context());
        long elapsed = System.currentTimeMillis() - start;

        // 3 steps at 100ms each: sequential would take ~300ms; parallel should be well under that.
        assertThat(elapsed).isLessThan(250);
        assertThat(metrics.avgStageSize()).isEqualTo(3.0);
    }

    @Test
    void partialFailureInStageDoesNotPreventOtherStepsFromCompleting() {
        CapabilityExecutor executor = (step, ctx) -> step.type() == CapabilityType.GITHUB_REVIEW
                ? new ExecutionResult(step.type(), Map.of(), false, 1, 5, "boom")
                : new ExecutionResult(step.type(), Map.of(), true, 1, 5, null);
        DefaultParallelCapabilityExecutor parallel = new DefaultParallelCapabilityExecutor(executor, metrics);
        List<CapabilityStep> stage = List.of(
                new CapabilityStep(CapabilityType.RESUME_ANALYSIS, CapabilityPriority.HIGH),
                new CapabilityStep(CapabilityType.GITHUB_REVIEW, CapabilityPriority.HIGH));

        Map<CapabilityType, ExecutionResult> results = parallel.executeStage(stage, context());

        assertThat(results.get(CapabilityType.RESUME_ANALYSIS).success()).isTrue();
        assertThat(results.get(CapabilityType.GITHUB_REVIEW).success()).isFalse();
    }
}
