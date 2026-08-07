package ai.careerpilot.execution.timeline;

/**
 * P5 — the fixed taxonomy every stage failure is filed under.
 *
 * <p>Exists so "which part of the pipeline is failing" is a {@code GROUP BY} rather than a
 * text search over free-form reasons. It answers a different question from the pre-existing
 * {@code ApplicationRetry} classes ({@code NETWORK}, {@code CAPTCHA}, {@code CONFIRMATION_MISSING},
 * ...), which exist to decide <em>what to do next</em> — retry, pause, stop. This one records
 * <em>which component broke</em>. The two are recorded side by side on a failed stage and neither
 * is derived from the other, because "retry it" and "the uploader is broken" are independently
 * useful facts.
 *
 * <p>{@link #UNKNOWN} is deliberately present and deliberately last. A failure that cannot be
 * honestly attributed must show up as unattributed rather than be filed under whichever category
 * looked closest — an inflated {@code VERIFICATION} count would send someone to fix the wrong
 * component.
 */
public enum FailureCategory {

    /** Chromium/Playwright itself: launch failure, crashed renderer, closed target, lease capacity. */
    BROWSER,
    /** Reaching the page: DNS, TLS, timeouts, HTTP errors, redirects away from the posting. */
    NAVIGATION,
    /** The page is not the application form we expected, or the ATS could not be identified. */
    ATS_DETECTION,
    /** The form could not be read: no controls discovered, everything behind a cross-origin frame. */
    QUESTION_PARSING,
    /** Controls were read but could not be answered from verified data. Never a fabrication. */
    QUESTION_RESOLUTION,
    /** Resume or cover-letter upload failed, or could not be verified as attached. */
    UPLOAD,
    /** Writing values into the form failed. */
    FIELD_FILL,
    /** The submission could not be confirmed after the click. */
    VERIFICATION,
    /** The submit click itself could not be issued. */
    SUBMIT,
    /** The result could not be written down — database unavailable, transaction lost. */
    PERSISTENCE,
    /** Dependencies outside the automation: storage, database, an external service. */
    INFRASTRUCTURE,
    /** Genuinely unattributed. Never used as a catch-all for "probably X". */
    UNKNOWN
}
