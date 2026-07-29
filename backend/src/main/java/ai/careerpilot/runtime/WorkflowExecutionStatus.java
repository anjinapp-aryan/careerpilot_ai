package ai.careerpilot.runtime;

/**
 * LangGraph Workflow Runtime — the lifecycle states a single {@link WorkflowExecutionResult} can
 * end in. This is a transport-level status (did the invocation succeed, fail, or not run at all),
 * not a business status — it says nothing about resume quality, interview readiness, etc.
 */
public enum WorkflowExecutionStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    TIMED_OUT,
    CANCELLED,
    INTERRUPTED
}
