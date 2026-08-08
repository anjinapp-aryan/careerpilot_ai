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
 * real producer would make the timeline claim work happened that never did, so there are none.
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
    FORM_DISCOVERED("Form Discovered"),

    QUESTIONS_EXTRACTED("Questions Extracted"),
    QUESTIONS_RESOLVED("Questions Resolved"),
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
