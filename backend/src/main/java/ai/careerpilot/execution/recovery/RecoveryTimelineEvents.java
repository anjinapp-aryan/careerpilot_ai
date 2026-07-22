package ai.careerpilot.execution.recovery;

/**
 * Phase 7.16.3 — free-text {@code eventType} constants appended via the existing {@code
 * TimelineService} (which, like {@code ApplicationExecutionAuditEntry}, has no shared enum today —
 * see {@code SubmissionVerificationService}'s own ad-hoc constants for the same precedent this
 * class follows).
 */
public final class RecoveryTimelineEvents {

    public static final String SOURCE = "AUTOMATION_RECOVERY";

    public static final String RECOVERY_STARTED = "RECOVERY_STARTED";
    public static final String RETRY_STARTED = "RETRY_STARTED";
    public static final String RETRY_SUCCEEDED = "RETRY_SUCCEEDED";
    public static final String RETRY_FAILED = "RETRY_FAILED";
    public static final String CHECKPOINT_RESTORED = "CHECKPOINT_RESTORED";
    public static final String MANUAL_REVIEW_REQUESTED = "MANUAL_REVIEW_REQUESTED";
    public static final String EXECUTION_CANCELLED = "EXECUTION_CANCELLED";
    public static final String AUTOMATION_RECOVERED = "AUTOMATION_RECOVERED";

    private RecoveryTimelineEvents() {}
}
