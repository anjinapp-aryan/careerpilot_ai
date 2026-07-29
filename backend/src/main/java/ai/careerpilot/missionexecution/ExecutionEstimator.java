package ai.careerpilot.missionexecution;

import ai.careerpilot.workflowplanner.WorkflowPlan;

import java.util.List;

/** Pre-Phase-9 Hardening — aggregates each {@link WorkflowPlan}'s own Phase 8 estimate plus this plan's decisions into one {@link MissionExecutionEstimate}. */
public interface ExecutionEstimator {

    MissionExecutionEstimate estimate(List<WorkflowPlan> plans, List<ExecutionDecision> decisions);
}
