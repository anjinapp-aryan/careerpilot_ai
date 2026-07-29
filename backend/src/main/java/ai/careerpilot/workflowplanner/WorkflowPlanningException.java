package ai.careerpilot.workflowplanner;

/** Phase 8 — thrown when no registry definition exists for a requested {@link WorkflowType}, or a produced plan fails validation. */
public class WorkflowPlanningException extends RuntimeException {

    public WorkflowPlanningException(String message) {
        super(message);
    }
}
