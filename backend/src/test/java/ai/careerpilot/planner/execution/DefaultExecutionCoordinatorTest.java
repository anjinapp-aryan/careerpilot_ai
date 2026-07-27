package ai.careerpilot.planner.execution;

import ai.careerpilot.capability.CapabilityType;
import ai.careerpilot.intent.IntentType;
import ai.careerpilot.mcp.McpExecutionContext;
import ai.careerpilot.planner.CapabilityDependencies;
import ai.careerpilot.planner.CapabilityPlan;
import ai.careerpilot.planner.CapabilityPriority;
import ai.careerpilot.planner.CapabilityStep;
import ai.careerpilot.planner.ExecutionOrder;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultExecutionCoordinatorTest {

    private final InMemoryMultiCapabilityMetrics metrics = new InMemoryMultiCapabilityMetrics();

    private McpExecutionContext context() {
        return new McpExecutionContext(UUID.randomUUID(), null, null, "trace", Duration.ofSeconds(5), Map.of());
    }

    @Test
    void nullOrEmptyPlan_returnsEmptyResultRatherThanExecuting() {
        ParallelCapabilityExecutor stageExecutor = (stage, ctx) -> { throw new AssertionError("should not be called"); };
        DefaultExecutionCoordinator coordinator = new DefaultExecutionCoordinator(stageExecutor, new DefaultResultMerger(), metrics);

        MultiCapabilityResult result = coordinator.execute(null, context());

        assertThat(result.results()).isEmpty();
        assertThat(result.allSucceeded()).isTrue();
    }

    @Test
    void multiStagePlan_executesStagesInOrder_sequentialBetweenStages() {
        CapabilityStep career = new CapabilityStep(CapabilityType.CAREER_STRATEGY, CapabilityPriority.HIGH);
        CapabilityStep jobs = new CapabilityStep(CapabilityType.JOB_RECOMMENDATION, CapabilityPriority.MEDIUM);
        CapabilityPlan plan = new CapabilityPlan(IntentType.EXECUTIVE_COACH, List.of(career, jobs),
                new CapabilityDependencies(Map.of(CapabilityType.JOB_RECOMMENDATION, Set.of(CapabilityType.CAREER_STRATEGY))),
                new ExecutionOrder(List.of(List.of(CapabilityType.CAREER_STRATEGY), List.of(CapabilityType.JOB_RECOMMENDATION))),
                "test");

        List<CapabilityType> executionOrderSeen = new CopyOnWriteArrayList<>();
        ParallelCapabilityExecutor stageExecutor = (stage, ctx) -> {
            Map<CapabilityType, ExecutionResult> results = new java.util.LinkedHashMap<>();
            for (CapabilityStep step : stage) {
                executionOrderSeen.add(step.type());
                results.put(step.type(), new ExecutionResult(step.type(), Map.of(), true, 1, 5, null));
            }
            return results;
        };
        DefaultExecutionCoordinator coordinator = new DefaultExecutionCoordinator(stageExecutor, new DefaultResultMerger(), metrics);

        MultiCapabilityResult result = coordinator.execute(plan, context());

        assertThat(executionOrderSeen).containsExactly(CapabilityType.CAREER_STRATEGY, CapabilityType.JOB_RECOMMENDATION);
        assertThat(result.results()).hasSize(2);
        assertThat(result.allSucceeded()).isTrue();
        assertThat(result.intentType()).isEqualTo(IntentType.EXECUTIVE_COACH);
    }

    @Test
    void partialFailureInEarlyStage_stillExecutesLaterStages() {
        CapabilityStep career = new CapabilityStep(CapabilityType.CAREER_STRATEGY, CapabilityPriority.HIGH);
        CapabilityStep jobs = new CapabilityStep(CapabilityType.JOB_RECOMMENDATION, CapabilityPriority.MEDIUM);
        CapabilityPlan plan = new CapabilityPlan(IntentType.EXECUTIVE_COACH, List.of(career, jobs),
                CapabilityDependencies.none(),
                new ExecutionOrder(List.of(List.of(CapabilityType.CAREER_STRATEGY), List.of(CapabilityType.JOB_RECOMMENDATION))),
                "test");

        ParallelCapabilityExecutor stageExecutor = (stage, ctx) -> {
            Map<CapabilityType, ExecutionResult> results = new java.util.LinkedHashMap<>();
            for (CapabilityStep step : stage) {
                boolean success = step.type() != CapabilityType.CAREER_STRATEGY;
                results.put(step.type(), new ExecutionResult(step.type(), Map.of(), success, 1, 5, success ? null : "boom"));
            }
            return results;
        };
        DefaultExecutionCoordinator coordinator = new DefaultExecutionCoordinator(stageExecutor, new DefaultResultMerger(), metrics);

        MultiCapabilityResult result = coordinator.execute(plan, context());

        assertThat(result.results()).hasSize(2);
        assertThat(result.results().get(CapabilityType.CAREER_STRATEGY).success()).isFalse();
        assertThat(result.results().get(CapabilityType.JOB_RECOMMENDATION).success()).isTrue();
        assertThat(result.allSucceeded()).isFalse();
    }

    @Test
    void stageExecutorThrows_returnsPartialResultsRatherThanPropagating() {
        CapabilityStep step = new CapabilityStep(CapabilityType.GITHUB_REVIEW, CapabilityPriority.HIGH);
        CapabilityPlan plan = new CapabilityPlan(IntentType.GITHUB_ANALYSIS, List.of(step),
                CapabilityDependencies.none(), new ExecutionOrder(List.of(List.of(CapabilityType.GITHUB_REVIEW))), "test");

        ParallelCapabilityExecutor throwingExecutor = (stage, ctx) -> { throw new RuntimeException("boom"); };
        DefaultExecutionCoordinator coordinator = new DefaultExecutionCoordinator(throwingExecutor, new DefaultResultMerger(), metrics);

        MultiCapabilityResult result = coordinator.execute(plan, context());

        assertThat(result.results()).isEmpty();
        assertThat(result.allSucceeded()).isTrue();
    }
}
