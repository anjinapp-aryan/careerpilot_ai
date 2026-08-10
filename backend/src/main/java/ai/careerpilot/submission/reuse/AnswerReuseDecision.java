package ai.careerpilot.submission.reuse;

/**
 * Reason code for whether a submission session's Step 6 (field mapping + question/answer
 * generation) copied a prior session's answers or regenerated them via the AI Gateway.
 * Deliberately explicit rather than a boolean — the UI and audit trail must be able to say WHY,
 * not just whether.
 */
public enum AnswerReuseDecision {
    /** No eligible prior session existed for this (user, job) — first Apply, generated from scratch. */
    FULL_BUILD,
    /** A compatible, unexpired prior session's answers were copied verbatim — no AI call made. */
    REUSED,
    /** A prior session existed but its answers were older than the reuse TTL. */
    REBUILT_EXPIRED,
    /** The resume tailoring used for this job changed since the prior session. */
    REBUILT_RESUME_CHANGED,
    /** The application package (which subsumes profile/cover-letter changes — a new package
     *  version is what actually reflects a profile edit) changed since the prior session. */
    REBUILT_PACKAGE_CHANGED,
    /** Company brief or STAR story context changed since the prior session. */
    REBUILT_CONTEXT_CHANGED
}
