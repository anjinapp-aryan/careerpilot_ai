package ai.careerpilot.missionexecution;

/**
 * Pre-Phase-9 Hardening — Mission Execution Engine. Converts a {@link ExecutionContext} (the
 * Workflow Planner's {@code WorkflowPlan}s plus mission/execution state) into a {@link
 * MissionExecutionPlan}: what executes now, what waits, what runs in parallel, what's blocked,
 * what needs approval, what retries. It decides execution order and policy — it never executes
 * a workflow itself, and it never decides WHAT should happen (that remains the Mission
 * Engine/Strategy Engine/Mission Orchestrator's job).
 */
public interface MissionExecutionEngine {

    MissionExecutionPlan plan(ExecutionContext context);
}
