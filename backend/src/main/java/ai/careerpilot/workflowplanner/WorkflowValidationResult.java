package ai.careerpilot.workflowplanner;

import java.util.List;

/** Phase 8 — {@link WorkflowValidator}'s output. */
public record WorkflowValidationResult(boolean valid, List<String> errors) {

    public static WorkflowValidationResult ok() {
        return new WorkflowValidationResult(true, List.of());
    }

    public static WorkflowValidationResult invalid(List<String> errors) {
        return new WorkflowValidationResult(false, errors);
    }
}
