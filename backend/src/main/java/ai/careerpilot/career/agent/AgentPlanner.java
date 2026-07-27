package ai.careerpilot.career.agent;

/** Phase 11.6 — decides which autonomous tasks an {@link AgentObservation} warrants. */
public interface AgentPlanner {

    AgentExecutionPlan plan(AgentObservation observation);
}
