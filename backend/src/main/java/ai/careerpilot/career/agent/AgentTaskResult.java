package ai.careerpilot.career.agent;

/** Phase 11.6 — the result of dispatching one planned task via {@link AgentTaskExecutor}. */
public record AgentTaskResult(AgentTaskType type, TaskOutcome outcome, String detail) {
}
