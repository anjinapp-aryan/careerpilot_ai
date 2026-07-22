package ai.careerpilot.execution.recovery;

/**
 * Phase 7.16.3 — named phases an {@link ai.careerpilot.domain.ApplicationExecution} passes through,
 * stamped onto its {@code checkpoint} column for observability ("which phase did we reach before
 * this failed?"). This is NOT a literal browser-state resume mechanism — {@link
 * ai.careerpilot.execution.execution.ApplicationExecutionService#retryExecution} always re-runs
 * {@code execute()} from scratch (a brand-new browser context/page via {@code
 * BrowserSessionManager#newContext()}), it never resumes mid-page. Only the checkpoints that
 * correspond to real, currently-implemented execution phases are defined here — the guest-apply
 * flow ({@code GuestApplyAutomationService}) never calls {@code uploadResume}/{@code
 * uploadCoverLetter}/{@code answerQuestions}, so no checkpoint claims those steps happened.
 */
public final class ExecutionCheckpoint {

    public static final String QUEUED = "QUEUED";
    public static final String VALIDATING = "VALIDATING";
    public static final String JOB_LOADED = "JOB_LOADED";
    public static final String FORM_FILLED = "FORM_FILLED";
    public static final String SUBMIT_CLICKED = "SUBMIT_CLICKED";
    public static final String VERIFICATION_STARTED = "VERIFICATION_STARTED";
    public static final String VERIFICATION_COMPLETE = "VERIFICATION_COMPLETE";

    private ExecutionCheckpoint() {}
}
