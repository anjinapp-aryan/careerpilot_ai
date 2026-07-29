package ai.careerpilot.workflowplanner;

/**
 * Phase 8 — how a {@link WorkflowPlan}'s steps are meant to be run once a future execution layer
 * (LangGraph, Phase 9+) exists. Chosen by {@link WorkflowPlanFactory} as a planning-time
 * classification only; this package never executes anything.
 */
public enum WorkflowExecutionStrategy {
    SEQUENTIAL,
    PARALLEL,
    CONDITIONAL,
    HUMAN_APPROVAL,
    RETRY,
    SKIP,
    RESUME_LATER,
    FUTURE_AI_LOOP
}
