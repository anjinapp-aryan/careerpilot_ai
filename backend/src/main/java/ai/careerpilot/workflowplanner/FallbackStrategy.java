package ai.careerpilot.workflowplanner;

/**
 * Phase 8 — a plan-level fallback policy description. {@code fallbackWorkflowType} is {@code null}
 * when the fallback is "escalate to a human" rather than another workflow. Not enforced by
 * anything today; a future executor reads it.
 */
public record FallbackStrategy(String description, WorkflowType fallbackWorkflowType) {

    public static FallbackStrategy escalateToHuman() {
        return new FallbackStrategy("Escalate to human review after retries are exhausted.", null);
    }
}
