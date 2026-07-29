package ai.careerpilot.runtime;

import java.util.List;

/**
 * LangGraph Workflow Runtime — the first lifecycle step: structural validation of a {@link
 * WorkflowExecutionRequest} before any registry lookup or invocation is attempted. Returns the
 * list of violations (empty means valid) rather than throwing directly, so callers can decide how
 * to react — {@link DefaultWorkflowRuntime} turns a non-empty list into a {@link
 * ValidationException}.
 */
public interface ExecutionRequestValidator {

    List<String> validate(WorkflowExecutionRequest request);
}
