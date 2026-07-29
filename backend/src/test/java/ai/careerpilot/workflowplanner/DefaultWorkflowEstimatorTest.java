package ai.careerpilot.workflowplanner;

import ai.careerpilot.capability.CapabilityType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultWorkflowEstimatorTest {

    private final DefaultWorkflowEstimator estimator = new DefaultWorkflowEstimator();

    private WorkflowStep step(int number, CapabilityType capability, Duration duration) {
        return new WorkflowStep(number, "s" + number, "d", capability, List.of(), List.of(), 1,
                Duration.ofMinutes(10), false, List.of(), false, duration, "n" + number);
    }

    @Test
    void durationSumsAllStepDurations() {
        List<WorkflowStep> steps = List.of(
                step(1, CapabilityType.RESUME_ANALYSIS, Duration.ofMinutes(2)),
                step(2, null, Duration.ofMinutes(3)));

        WorkflowEstimate estimate = estimator.estimate(WorkflowType.RESUME, steps, WorkflowPriority.MEDIUM);

        assertThat(estimate.estimatedDuration()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void onlyCapabilityBearingStepsCountTowardAiCallsAndTokens() {
        List<WorkflowStep> steps = List.of(
                step(1, CapabilityType.RESUME_ANALYSIS, Duration.ofMinutes(1)),
                step(2, CapabilityType.RESUME_ANALYSIS, Duration.ofMinutes(1)),
                step(3, null, Duration.ofMinutes(1)));

        WorkflowEstimate estimate = estimator.estimate(WorkflowType.RESUME, steps, WorkflowPriority.MEDIUM);

        assertThat(estimate.expectedAiCalls()).isEqualTo(2);
        assertThat(estimate.requiredMcpCalls()).isEqualTo(2);
        assertThat(estimate.approxTokenUsage()).isEqualTo(3000L);
    }

    @Test
    void complexityBucketsOnStepCount() {
        List<WorkflowStep> two = List.of(step(1, null, Duration.ZERO), step(2, null, Duration.ZERO));
        List<WorkflowStep> five = List.of(step(1, null, Duration.ZERO), step(2, null, Duration.ZERO),
                step(3, null, Duration.ZERO), step(4, null, Duration.ZERO), step(5, null, Duration.ZERO));
        List<WorkflowStep> eight = List.of(step(1, null, Duration.ZERO), step(2, null, Duration.ZERO),
                step(3, null, Duration.ZERO), step(4, null, Duration.ZERO), step(5, null, Duration.ZERO),
                step(6, null, Duration.ZERO), step(7, null, Duration.ZERO), step(8, null, Duration.ZERO));

        assertThat(estimator.estimate(WorkflowType.RESUME, two, WorkflowPriority.MEDIUM).complexity()).isEqualTo(WorkflowComplexity.LOW);
        assertThat(estimator.estimate(WorkflowType.RESUME, five, WorkflowPriority.MEDIUM).complexity()).isEqualTo(WorkflowComplexity.MEDIUM);
        assertThat(estimator.estimate(WorkflowType.RESUME, eight, WorkflowPriority.MEDIUM).complexity()).isEqualTo(WorkflowComplexity.HIGH);
    }

    @Test
    void criticalPriorityLowersConfidence() {
        List<WorkflowStep> steps = List.of(step(1, null, Duration.ZERO));

        double normal = estimator.estimate(WorkflowType.RESUME, steps, WorkflowPriority.MEDIUM).confidence();
        double critical = estimator.estimate(WorkflowType.RESUME, steps, WorkflowPriority.CRITICAL).confidence();

        assertThat(critical).isLessThan(normal);
    }
}
