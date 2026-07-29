package ai.careerpilot.runtime;

/**
 * LangGraph Workflow Runtime — the single public entry point. Answers exactly one question, "how
 * do I execute this workflow" — never "what should the workflow do" (that's already decided, by
 * the Mission Execution Engine, before a {@link WorkflowExecutionRequest} is ever built). Never
 * throws past this boundary: every outcome, including a validation or resolution failure, is
 * returned as a {@link WorkflowExecutionResult} with an appropriate {@link
 * WorkflowExecutionStatus} and populated {@code errors} — see {@link DefaultWorkflowRuntime}.
 */
public interface WorkflowRuntime {

    WorkflowExecutionResult execute(WorkflowExecutionRequest request);
}
