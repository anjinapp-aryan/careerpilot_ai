package ai.careerpilot.workflowplanner;

/** Phase 8 — a plan-level retry policy description. Not enforced by anything today; a future executor reads it. */
public record RetryStrategy(int maxRetries, String backoffDescription) {

    public static RetryStrategy standard() {
        return new RetryStrategy(2, "Exponential backoff, capped at 3 attempts total.");
    }
}
