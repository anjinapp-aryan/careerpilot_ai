package ai.careerpilot.career.agent;

/**
 * Phase 11.6 — the outcome of one {@link AgentTaskType} dispatch. {@code DEFERRED} is what
 * {@link DeferredAgentTaskExecutor} (the only implementation shipped in this phase) always
 * returns — see its own javadoc for why actual task execution is deliberately not connected to
 * any real business service yet.
 */
public enum TaskOutcome {
    EXECUTED,
    DEFERRED,
    FAILED
}
