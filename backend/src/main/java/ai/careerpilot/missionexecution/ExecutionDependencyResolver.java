package ai.careerpilot.missionexecution;

import ai.careerpilot.workflowplanner.WorkflowType;

/**
 * Pre-Phase-9 Hardening — decides whether a {@link WorkflowType} is currently {@link
 * ExecutionPolicy#BLOCKED}: either by an unmet {@link Precondition} (evaluated against {@link
 * ExecutionContext#metrics()}) or by a prerequisite workflow that hasn't reached {@link
 * ExecutionState#COMPLETED} in {@link ExecutionContext#currentStates()}.
 */
public interface ExecutionDependencyResolver {

    DependencyEvaluation evaluate(WorkflowType type, ExecutionContext context);
}
