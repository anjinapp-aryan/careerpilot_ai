package ai.careerpilot.runtime;

/** LangGraph Workflow Runtime — the underlying executor call (e.g. the agent-service HTTP request) exceeded its configured read timeout. */
public class WorkflowTimeoutException extends RuntimeException {

    public WorkflowTimeoutException(String workflowId, String executionId, Throwable cause) {
        super("Workflow execution timed out: workflowId=" + workflowId + ", executionId=" + executionId, cause);
    }
}
