package ai.careerpilot.missionexecution;

import ai.careerpilot.workflowplanner.WorkflowEstimate;
import ai.careerpilot.workflowplanner.WorkflowPlan;
import ai.careerpilot.workflowplanner.WorkflowType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultExecutionEstimatorTest {

    private final DefaultExecutionEstimator estimator = new DefaultExecutionEstimator();

    private WorkflowPlan planWith(Duration duration, double confidence) {
        WorkflowEstimate estimate = new WorkflowEstimate(duration, 0, java.math.BigDecimal.ZERO, 0, 0, null, confidence);
        WorkflowPlan plan = mock(WorkflowPlan.class);
        when(plan.estimate()).thenReturn(estimate);
        return plan;
    }

    private ExecutionDecision decision(WorkflowType type, ExecutionPolicy policy, int week) {
        return new ExecutionDecision(type, policy, ExecutionPriority.NORMAL, week, List.of(), List.of(), null, "r");
    }

    @Test
    void sumsDurationAcrossAllPlans() {
        List<WorkflowPlan> plans = List.of(planWith(Duration.ofMinutes(10), 0.6), planWith(Duration.ofMinutes(5), 0.8));

        MissionExecutionEstimate estimate = estimator.estimate(plans, List.of());

        assertThat(estimate.totalEstimatedDuration()).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void countsApprovalRequiredDecisions() {
        List<ExecutionDecision> decisions = List.of(
                decision(WorkflowType.RESUME, ExecutionPolicy.APPROVAL_REQUIRED, 1),
                decision(WorkflowType.ATS, ExecutionPolicy.AUTO, 1),
                decision(WorkflowType.INTERVIEW, ExecutionPolicy.APPROVAL_REQUIRED, 2));

        MissionExecutionEstimate estimate = estimator.estimate(List.of(), decisions);

        assertThat(estimate.totalApprovalsRequired()).isEqualTo(2);
        assertThat(estimate.totalWeeks()).isEqualTo(2);
    }

    @Test
    void averagesConfidenceAcrossPlans() {
        List<WorkflowPlan> plans = List.of(planWith(Duration.ZERO, 0.4), planWith(Duration.ZERO, 0.8));

        MissionExecutionEstimate estimate = estimator.estimate(plans, List.of());

        assertThat(estimate.aggregateConfidence()).isEqualTo(0.6, org.assertj.core.data.Offset.offset(0.0001));
    }
}
