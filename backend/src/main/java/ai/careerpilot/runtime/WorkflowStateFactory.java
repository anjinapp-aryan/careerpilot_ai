package ai.careerpilot.runtime;

import java.util.Map;

/**
 * LangGraph Workflow Runtime — builds the {@link WorkflowState} handed to a {@link
 * WorkflowExecutor}, from an already-built {@link WorkflowExecutionContext} plus the caller's
 * opaque {@link WorkflowExecutionRequest#inputs()}.
 */
public interface WorkflowStateFactory {

    WorkflowState create(WorkflowExecutionContext context, Map<String, Object> inputs);
}
