package ai.careerpilot.runtime;

/** LangGraph Workflow Runtime — the execution was cancelled before or during invocation (e.g. the calling thread was interrupted). */
public class WorkflowCancelledException extends RuntimeException {

    public WorkflowCancelledException(String workflowId, String executionId) {
        super("Workflow execution cancelled: workflowId=" + workflowId + ", executionId=" + executionId);
    }
}
