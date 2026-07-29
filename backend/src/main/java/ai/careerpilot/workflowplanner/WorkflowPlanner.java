package ai.careerpilot.workflowplanner;

/**
 * Phase 8 — Enterprise Workflow Planner. Converts a mission recommendation ({@link
 * WorkflowPlanRequest}: which workflow, for which mission/strategy, at what priority) into an
 * executable {@link WorkflowPlan} — steps, execution strategy, estimates, approval/retry/fallback
 * policy. Decides HOW a capability should execute; it never decides WHAT should happen (that's
 * the Mission Engine/Strategy Engine/Mission Orchestrator) and it never executes anything itself
 * (no Spring AI, MCP, LangGraph, or AI Gateway calls anywhere in this package).
 */
public interface WorkflowPlanner {

    WorkflowPlan plan(WorkflowPlanRequest request);
}
