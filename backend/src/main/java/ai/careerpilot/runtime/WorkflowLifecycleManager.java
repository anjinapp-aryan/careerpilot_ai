package ai.careerpilot.runtime;

/**
 * LangGraph Workflow Runtime — owns the timing/event bookkeeping for one execution's {@link
 * ExecutionTrace}: when it started, each phase it passed through, and how it ended (completed or
 * failed). {@link DefaultWorkflowRuntime} calls this at each lifecycle step rather than touching
 * {@link ExecutionTrace} directly, keeping the orchestrator free of timing/logging detail.
 */
public interface WorkflowLifecycleManager {

    ExecutionTrace begin(WorkflowExecutionContext context);

    void recordPhase(ExecutionTrace trace, String phase, String message);

    void complete(ExecutionTrace trace, WorkflowExecutorOutcome outcome);

    void fail(ExecutionTrace trace, String phase, Throwable error);
}
