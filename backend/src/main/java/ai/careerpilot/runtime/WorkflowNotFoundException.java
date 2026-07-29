package ai.careerpilot.runtime;

/** LangGraph Workflow Runtime — the Workflow Registry (Phase 4) has no active definition for the requested {@code WorkflowType}. */
public class WorkflowNotFoundException extends RuntimeException {

    public WorkflowNotFoundException(String workflowType) {
        super("No active workflow definition found for type: " + workflowType);
    }
}
