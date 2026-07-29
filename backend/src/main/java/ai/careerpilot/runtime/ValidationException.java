package ai.careerpilot.runtime;

import java.util.List;

/** LangGraph Workflow Runtime — a {@link WorkflowExecutionRequest} failed structural validation before any resolution/execution was attempted. */
public class ValidationException extends RuntimeException {

    private final List<String> violations;

    public ValidationException(List<String> violations) {
        super("Execution request failed validation: " + violations);
        this.violations = List.copyOf(violations);
    }

    public List<String> violations() {
        return violations;
    }
}
