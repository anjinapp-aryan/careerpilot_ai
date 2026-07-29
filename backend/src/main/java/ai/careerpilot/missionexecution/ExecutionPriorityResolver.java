package ai.careerpilot.missionexecution;

import ai.careerpilot.workflowplanner.WorkflowPlan;

/** Pre-Phase-9 Hardening — maps a {@link WorkflowPlan} (which already carries a Phase 8 {@code WorkflowPriority}) plus mission context to this layer's richer {@link ExecutionPriority}. */
public interface ExecutionPriorityResolver {

    ExecutionPriority resolve(WorkflowPlan plan, ExecutionContext context);
}
