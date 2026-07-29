package ai.careerpilot.runtime;

/**
 * LangGraph Workflow Runtime — builds the {@link WorkflowExecutionContext} for one request, after
 * validation and registry resolution have both succeeded.
 */
public interface WorkflowContextFactory {

    WorkflowExecutionContext create(WorkflowExecutionRequest request, ResolvedWorkflowDefinition definition,
                                     String executionId);
}
