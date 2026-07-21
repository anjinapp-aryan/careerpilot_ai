package ai.careerpilot.execution.verification;

/**
 * Phase 7.16.1 — the only four honest outcomes of asking "did this submission actually reach the
 * ATS?" Deliberately NOT a boolean: {@code UNABLE_TO_VERIFY} and {@code UNKNOWN} exist specifically
 * so "we don't have proof" is never collapsed into either "yes" or "no" — see
 * {@code SubmissionVerificationService} for how each maps to the submission pipeline's outcome.
 */
public enum VerificationStatus {
    /** Real, checkable evidence exists (e.g. a non-fabricated confirmation reference was captured). */
    VERIFIED,
    /** The connector positively determined the submission did NOT go through. */
    NOT_VERIFIED,
    /** A submission attempt happened but no evidence could be captured/checked — never assume success. */
    UNABLE_TO_VERIFY,
    /** No connector could be resolved, or verification itself failed unexpectedly. */
    UNKNOWN
}
