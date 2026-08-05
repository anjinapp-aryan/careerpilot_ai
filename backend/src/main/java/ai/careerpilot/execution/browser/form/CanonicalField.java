package ai.careerpilot.execution.browser.form;

/**
 * Phase 12C — what a discovered form field <em>means</em>, independent of how it is rendered.
 *
 * <p>This enum is deliberately closed and small. Every value listed here has a real, auditable
 * source in this platform — {@code User}, {@code CandidateProfile}, {@code Resume},
 * {@code CoverLetter}, or a persisted {@code ApplicationSubmissionAnswer}. Fields that a real ATS
 * form asks for but this platform genuinely cannot answer (phone, LinkedIn, GitHub, portfolio —
 * confirmed absent from every entity by {@code FieldMappingService}'s own audited javadoc) are
 * still enumerated, because <b>naming a gap is how it stays visible</b>. They resolve to
 * "unresolved, no verified source" and are reported, never filled with a placeholder.
 *
 * <p>{@link #SCREENING_QUESTION} is the catch-all for a free-text question whose label classifies
 * to a {@code QuestionCategory} with a stored answer. {@link #UNKNOWN} is everything else and is
 * never filled.
 */
public enum CanonicalField {

    FIRST_NAME,
    LAST_NAME,
    FULL_NAME,
    EMAIL,

    // ── Phase C: these four previously had no source anywhere in the schema and were listed here
    // purely so the gap stayed visible. `candidate_ats_profile` now backs all of them. ──
    PHONE,
    LINKEDIN_URL,
    GITHUB_URL,
    PORTFOLIO_URL,

    // ── Phase C: location, employment, education and work authorisation. Every value below is
    // backed by a real column and resolved through FieldMappingService; none is inferred. ──
    COUNTRY,
    CITY,
    STATE,
    ADDRESS,
    POSTAL_CODE,
    PERSONAL_WEBSITE,

    CURRENT_COMPANY,
    CURRENT_TITLE,
    CURRENT_SALARY,

    HIGHEST_EDUCATION,
    DEGREE,
    FIELD_OF_STUDY,
    UNIVERSITY,
    GRADUATION_YEAR,

    WORK_AUTHORIZATION,
    VISA_STATUS,
    CITIZENSHIP,
    SECURITY_CLEARANCE,

    LANGUAGES,
    CERTIFICATIONS,

    RESUME_UPLOAD,
    COVER_LETTER_UPLOAD,
    COVER_LETTER_TEXT,

    YEARS_EXPERIENCE,
    SKILLS,
    SALARY_EXPECTATION,
    NOTICE_PERIOD,
    VISA_SPONSORSHIP,
    RELOCATION,
    REMOTE_PREFERENCE,

    /** A free-text question answered from a persisted {@code ApplicationSubmissionAnswer}. */
    SCREENING_QUESTION,

    /** Discovered but unrecognised. Always left blank and reported. */
    UNKNOWN;

    /** True for the three file-bearing fields, which take a path rather than a string value. */
    public boolean isFileUpload() {
        return this == RESUME_UPLOAD || this == COVER_LETTER_UPLOAD;
    }

    /**
     * True when this platform has no data source for the field at all, as opposed to merely not
     * having a value for a particular user. The distinction matters in reporting: "we can never
     * answer this" is a product gap, "this user has not set it" is a profile gap.
     *
     * <p><b>Phase C emptied this set.</b> It previously held PHONE, LINKEDIN_URL, GITHUB_URL and
     * PORTFOLIO_URL; {@code candidate_ats_profile} now backs every one of them, so the honest
     * answer for an absent phone number changed from "this platform cannot ever answer this" to
     * "this user has not filled it in" — which is a prompt to complete a profile rather than a
     * permanent product gap. The method is retained rather than deleted because the distinction is
     * real and the next unbacked field should be declared here rather than silently guessed at.
     */
    public boolean hasNoDataSource() {
        return false;
    }
}
