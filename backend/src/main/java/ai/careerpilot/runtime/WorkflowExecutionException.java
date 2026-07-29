package ai.careerpilot.runtime;

/** LangGraph Workflow Runtime — a {@link WorkflowExecutor} invocation failed for a reason other than timeout or cancellation. */
public class WorkflowExecutionException extends RuntimeException {

    private final String workflowId;
    private final String executionId;

    public WorkflowExecutionException(String workflowId, String executionId, String message, Throwable cause) {
        super(message, cause);
        this.workflowId = workflowId;
        this.executionId = executionId;
    }

    public String workflowId() {
        return workflowId;
    }

    public String executionId() {
        return executionId;
    }
}
