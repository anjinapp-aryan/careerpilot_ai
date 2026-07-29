package ai.careerpilot.workflowplanner;

/**
 * Phase 8 — sanity-checks a {@link WorkflowPlan} before it's handed back to the caller: at least
 * one step, unique step numbers, dependencies that reference real step numbers, no step
 * depending on itself. Never mutates the plan — a failing validation causes {@link
 * WorkflowPlanner#plan} to throw {@link WorkflowPlanningException} rather than return a broken plan.
 */
public interface WorkflowValidator {

    WorkflowValidationResult validate(WorkflowPlan plan);
}
