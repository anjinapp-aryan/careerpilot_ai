package ai.careerpilot.execution.timeline;

/**
 * P5 — the named stages one application-execution run passes through.
 *
 * <p>This is deliberately a richer, ordered taxonomy than the pre-existing {@code
 * ExecutionCheckpoint} constants, and it does <b>not</b> replace them: {@code checkpoint} remains
 * the single "furthest phase reached" column that {@code AutomationRecoveryService} already reads.
 * This enum is the grain a <em>timeline</em> needs — every stage, in order, each with its own start
 * and end — which a single overwritten column structurally cannot express.
 *
 * <p><b>Only stages this platform can genuinely reach are listed.</b> The declaration order is the
 * order a healthy run visits them, so {@link #ordinal()} is a legitimate "how far did it get"
 * comparison, and {@link #displayName()} is what the Operations Center renders. A stage with no
 * real producer would make the timeline claim work happened that never did, so there are none —
 * with one documented exception, {@link #ANSWER_CONFIDENCE_COMPUTED}, kept declared but
 * deliberately unproduced (see its own javadoc for why).
 */
public enum ExecutionStage {

    CREATED("Application Created"),
    WAITING_FOR_APPROVAL("Waiting for Approval"),
    APPROVAL_GRANTED("Approval Granted"),
    CLAIMED_FOR_SUBMISSION("Claimed for Submission"),

    BROWSER_LEASE_ACQUIRED("Browser Lease Acquired"),
    BROWSER_STARTED("Browser Started"),

    NAVIGATION_STARTED("Navigation Started"),
    NAVIGATION_COMPLETED("Navigation Completed"),

    ATS_IDENTIFIED("ATS Identified"),
    PAGE_CLASSIFIED("Page Classified"),
    /** P7 Action 4 — the timed pair for discovery; {@link #FORM_DISCOVERED} is its instant completion mark. */
    FORM_DISCOVERY_STARTED("Form Discovery Started"),
    FORM_DISCOVERED("Form Discovered"),

    QUESTIONS_EXTRACTED("Questions Extracted"),
    QUESTIONS_RESOLVED("Questions Resolved"),
    /**
     * P7 Action 4 — audited and left deliberately unproduced. No uniform "compute confidence" step
     * exists across the resolution pipeline: a confidence band is only ever computed for the one
     * {@code AnswerResolver} source that consults the employer answer library (see {@code
     * AnswerResolver#fromEmployerLibrary}); every other source resolves deterministically with no
     * confidence concept at all. Emitting this stage for the whole plan would claim a computation
     * that, for most fields, never happens — exactly what this timeline's own design principle
     * ("a stage with no real producer would make the timeline claim work happened that never did")
     * forbids. Kept declared (not removed) so a future phase that adds a genuine per-plan confidence
     * computation has a name already reserved for it.
     */
    ANSWER_CONFIDENCE_COMPUTED("Answer Confidence Computed"),

    DOCUMENT_UPLOAD_STARTED("Document Upload Started"),
    DOCUMENT_UPLOAD_COMPLETED("Document Upload Completed"),

    FIELD_FILL_STARTED("Field Fill Started"),
    FIELD_FILL_COMPLETED("Field Fill Completed"),

    VERIFICATION_STARTED("Verification Started"),
    VERIFICATION_COMPLETED("Verification Completed"),

    SUBMIT_CLICK_STARTED("Submit Click Started"),
    SUBMIT_CLICK_COMPLETED("Submit Click Completed"),
    CONFIRMATION_DETECTED("Confirmation Detected"),

    RESULT_PERSISTED("Result Persisted"),
    COMPLETED("Workflow Finished"),
    FAILED("Failed"),
    SUBMIT_UNVERIFIED("Submit Unverified"),
    PAUSED("Paused for Human Review");

    private final String displayName;

    ExecutionStage(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /**
     * True for the stages that end a run. Used by the read side to decide whether a timeline is
     * still in flight or genuinely stopped — an open last stage means "running", not "stuck".
     */
    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == SUBMIT_UNVERIFIED || this == PAUSED;
    }
}
