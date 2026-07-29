package ai.careerpilot.missionexecution;

import ai.careerpilot.workflowplanner.WorkflowComplexity;
import ai.careerpilot.workflowplanner.WorkflowPlan;
import ai.careerpilot.workflowplanner.WorkflowPriority;

/**
 * Pre-Phase-9 Hardening — the only {@link ExecutionPriorityResolver}: a direct, deterministic
 * mapping from Phase 8's {@link WorkflowPriority} to this layer's {@link ExecutionPriority},
 * with one refinement — a {@code LOW}-priority, {@code LOW}-complexity plan downgrades to {@link
 * ExecutionPriority#OPTIONAL} (the one value {@link WorkflowPriority} has no equivalent for).
 */
public class DefaultExecutionPriorityResolver implements ExecutionPriorityResolver {

    @Override
    public ExecutionPriority resolve(WorkflowPlan plan, ExecutionContext context) {
        return switch (plan.priority()) {
            case CRITICAL -> ExecutionPriority.CRITICAL;
            case HIGH -> ExecutionPriority.HIGH;
            case MEDIUM -> ExecutionPriority.NORMAL;
            case LOW -> plan.estimatedComplexity() == WorkflowComplexity.LOW
                    ? ExecutionPriority.OPTIONAL
                    : ExecutionPriority.LOW;
        };
    }
}
