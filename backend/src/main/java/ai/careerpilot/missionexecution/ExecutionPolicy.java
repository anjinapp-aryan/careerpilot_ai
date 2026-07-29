package ai.careerpilot.missionexecution;

/**
 * Pre-Phase-9 Hardening — Mission Execution Engine. How one {@link ExecutionDecision} should be
 * treated by a future executor. This package never applies these itself — it only assigns them.
 */
public enum ExecutionPolicy {
    AUTO,
    MANUAL,
    BACKGROUND,
    APPROVAL_REQUIRED,
    SEQUENTIAL,
    PARALLEL,
    SCHEDULED,
    RETRY,
    WAIT,
    BLOCKED,
    SKIPPED,
    CANCELLED
}
