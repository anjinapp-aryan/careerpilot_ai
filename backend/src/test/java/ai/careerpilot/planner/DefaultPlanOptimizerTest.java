package ai.careerpilot.planner;

import ai.careerpilot.capability.CapabilityType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultPlanOptimizerTest {

    private final InMemoryCapabilityPlannerMetrics metrics = new InMemoryCapabilityPlannerMetrics();
    private final DefaultPlanOptimizer optimizer = new DefaultPlanOptimizer(metrics);

    @Test
    void emptyStepsProduceEmptyOrder() {
        ExecutionOrder order = optimizer.optimize(List.of(), CapabilityDependencies.none());
        assertThat(order.stages()).isEmpty();
    }

    @Test
    void independentStepsAllLandInOneParallelStage() {
        List<CapabilityStep> steps = List.of(
                new CapabilityStep(CapabilityType.RESUME_ANALYSIS, CapabilityPriority.HIGH),
                new CapabilityStep(CapabilityType.GITHUB_REVIEW, CapabilityPriority.HIGH));

        ExecutionOrder order = optimizer.optimize(steps, CapabilityDependencies.none());

        assertThat(order.stages()).hasSize(1);
        assertThat(order.stages().get(0)).containsExactlyInAnyOrder(
                CapabilityType.RESUME_ANALYSIS, CapabilityType.GITHUB_REVIEW);
    }

    @Test
    void dependentStepSplitsIntoTwoOrderedStages() {
        List<CapabilityStep> steps = List.of(
                new CapabilityStep(CapabilityType.CAREER_STRATEGY, CapabilityPriority.HIGH),
                new CapabilityStep(CapabilityType.JOB_RECOMMENDATION, CapabilityPriority.MEDIUM));
        CapabilityDependencies deps = new CapabilityDependencies(
                Map.of(CapabilityType.JOB_RECOMMENDATION, Set.of(CapabilityType.CAREER_STRATEGY)));

        ExecutionOrder order = optimizer.optimize(steps, deps);

        assertThat(order.stages()).hasSize(2);
        assertThat(order.stages().get(0)).containsExactly(CapabilityType.CAREER_STRATEGY);
        assertThat(order.stages().get(1)).containsExactly(CapabilityType.JOB_RECOMMENDATION);
    }

    @Test
    void dependencyOnCapabilityNotInPlanIsIgnoredRatherThanBlocking() {
        List<CapabilityStep> steps = List.of(
                new CapabilityStep(CapabilityType.GITHUB_REVIEW, CapabilityPriority.HIGH));
        // Depends on a capability that isn't part of this plan at all.
        CapabilityDependencies deps = new CapabilityDependencies(
                Map.of(CapabilityType.GITHUB_REVIEW, Set.of(CapabilityType.LEARNING_HELP)));

        ExecutionOrder order = optimizer.optimize(steps, deps);

        assertThat(order.stages()).hasSize(1);
        assertThat(order.stages().get(0)).containsExactly(CapabilityType.GITHUB_REVIEW);
    }

    @Test
    void cycleDegradesToOneStageRatherThanLoopingOrThrowing() {
        List<CapabilityStep> steps = List.of(
                new CapabilityStep(CapabilityType.CAREER_STRATEGY, CapabilityPriority.HIGH),
                new CapabilityStep(CapabilityType.JOB_RECOMMENDATION, CapabilityPriority.MEDIUM));
        CapabilityDependencies cyclic = new CapabilityDependencies(Map.of(
                CapabilityType.CAREER_STRATEGY, Set.of(CapabilityType.JOB_RECOMMENDATION),
                CapabilityType.JOB_RECOMMENDATION, Set.of(CapabilityType.CAREER_STRATEGY)));

        ExecutionOrder order = optimizer.optimize(steps, cyclic);

        assertThat(order.stages()).hasSize(1);
        assertThat(order.stages().get(0)).containsExactlyInAnyOrder(
                CapabilityType.CAREER_STRATEGY, CapabilityType.JOB_RECOMMENDATION);
        assertThat(metrics.cycleDetectionCount()).isEqualTo(1);
    }
}
