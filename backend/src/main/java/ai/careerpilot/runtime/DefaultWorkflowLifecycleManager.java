package ai.careerpilot.runtime;

/** LangGraph Workflow Runtime — the only {@link WorkflowLifecycleManager}. Pure bookkeeping, no branching logic. */
public class DefaultWorkflowLifecycleManager implements WorkflowLifecycleManager {

    @Override
    public ExecutionTrace begin(WorkflowExecutionContext context) {
        ExecutionTrace trace = new ExecutionTrace();
        trace.start();
        trace.record(ExecutionEvent.info("STARTED", "Execution started for workflow "
                + context.definition().workflowId() + " (executionId=" + context.executionId() + ")"));
        return trace;
    }

    @Override
    public void recordPhase(ExecutionTrace trace, String phase, String message) {
        trace.record(ExecutionEvent.info(phase, message));
    }

    @Override
    public void complete(ExecutionTrace trace, WorkflowExecutorOutcome outcome) {
        trace.record(ExecutionEvent.info("COMPLETED", "Execution finished with status " + outcome.status()));
        trace.end();
    }

    @Override
    public void fail(ExecutionTrace trace, String phase, Throwable error) {
        trace.record(ExecutionEvent.error(phase, error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
        trace.end();
    }
}
