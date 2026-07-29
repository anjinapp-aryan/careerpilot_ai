package ai.careerpilot.missionexecution;

import java.time.Duration;

/** Pre-Phase-9 Hardening — aggregate, plan-level estimates. Sums/derives from each {@link ai.careerpilot.workflowplanner.WorkflowPlan}'s own {@code WorkflowEstimate} (Phase 8) rather than recomputing. */
public record MissionExecutionEstimate(Duration totalEstimatedDuration, int totalApprovalsRequired,
                                        int totalWeeks, double aggregateConfidence) {
}
