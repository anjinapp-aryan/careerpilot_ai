package ai.careerpilot.missionexecution;

/** Pre-Phase-9 Hardening — a workflow's current lifecycle state, as supplied by the caller in {@link ExecutionContext#currentStates()}. This package never mutates it. */
public enum ExecutionState {
    PENDING,
    QUEUED,
    RUNNING,
    WAITING,
    BLOCKED,
    RETRY_PENDING,
    APPROVAL_PENDING,
    COMPLETED,
    FAILED,
    CANCELLED,
    SKIPPED
}
