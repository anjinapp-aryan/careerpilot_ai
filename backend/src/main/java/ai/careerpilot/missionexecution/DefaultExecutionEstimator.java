package ai.careerpilot.missionexecution;

import ai.careerpilot.workflowplanner.WorkflowPlan;

import java.time.Duration;
import java.util.List;

/**
 * Pre-Phase-9 Hardening — the only {@link ExecutionEstimator}. Sums each {@link WorkflowPlan}'s
 * own Phase 8 {@code WorkflowEstimate} duration, counts decisions requiring approval, takes the
 * highest assigned week as the total timeline length, and averages each plan's own confidence.
 */
public class DefaultExecutionEstimator implements ExecutionEstimator {

    @Override
    public MissionExecutionEstimate estimate(List<WorkflowPlan> plans, List<ExecutionDecision> decisions) {
        Duration totalDuration = plans.stream()
                .map(p -> p.estimate() == null ? Duration.ZERO : p.estimate().estimatedDuration())
                .reduce(Duration.ZERO, Duration::plus);

        int approvals = (int) decisions.stream().filter(d -> d.policy() == ExecutionPolicy.APPROVAL_REQUIRED).count();
        int totalWeeks = decisions.stream().mapToInt(ExecutionDecision::weekNumber).max().orElse(0);

        double avgConfidence = plans.stream()
                .filter(p -> p.estimate() != null)
                .mapToDouble(p -> p.estimate().confidence())
                .average()
                .orElse(0.0);

        return new MissionExecutionEstimate(totalDuration, approvals, totalWeeks, avgConfidence);
    }
}
