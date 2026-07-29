package ai.careerpilot.missionexecution;

/**
 * Pre-Phase-9 Hardening — Mission Execution Engine's own priority scale, richer than {@link
 * ai.careerpilot.workflowplanner.WorkflowPriority} (Phase 8, LOW/MEDIUM/HIGH/CRITICAL). Kept as a
 * separate enum rather than modifying Phase 8's — that one is already shipped, tested, and used
 * by {@code WorkflowPlanRequest}/{@code WorkflowPlan}; changing it would be a breaking change for
 * zero benefit. {@link DefaultExecutionPriorityResolver} is the one place the two are reconciled.
 */
public enum ExecutionPriority {
    CRITICAL, HIGH, NORMAL, LOW, OPTIONAL
}
