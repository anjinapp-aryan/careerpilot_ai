package ai.careerpilot.employerquestion;

import java.util.Locale;

/**
 * Phase D — where an employer answer came from, and whether browser automation may use it.
 *
 * <p>This mirrors {@code FieldVerificationSource} (Phase C) deliberately rather than reusing it.
 * That enum describes the provenance of a <em>profile fact</em> the candidate typed; this one
 * describes the provenance of an <em>answer to a question</em>, which can additionally be derived
 * from a résumé or a profile lookup. Merging them would force one concept to carry two vocabularies
 * and make the trust rule harder to state than "these four may be used, these two may not".
 *
 * <p>{@link #AI_SUGGESTED} exists so a draft can be shown to a human. It is never usable: an answer
 * a model wrote and nobody read is exactly the fabrication this phase must prevent, and it would be
 * delivered to an employer under the candidate's own name.
 */
public enum AnswerConfidence {

    /** Explicitly verified — the strongest band. */
    VERIFIED(true),
    /** A human read this exact answer and approved it. */
    HUMAN_APPROVED(true),
    /** Read from the candidate's own résumé, a document they authored and already send to employers. */
    RESUME_DERIVED(true),
    /** Read from a verified Candidate ATS Profile field. */
    PROFILE_DERIVED(true),

    /** Drafted by a model and not yet reviewed. <b>Never usable by automation.</b> */
    AI_SUGGESTED(false),
    /** Provenance could not be established. Untrusted by default. */
    UNKNOWN(false);

    private final boolean usableByAutomation;

    AnswerConfidence(boolean usableByAutomation) {
        this.usableByAutomation = usableByAutomation;
    }

    /** Whether an answer at this confidence may be typed into a live employer form. */
    public boolean isUsableByAutomation() {
        return usableByAutomation;
    }

    /**
     * Parse a stored band. An unrecognised or missing value becomes {@link #UNKNOWN}, which is not
     * usable — failing closed, since the alternative is treating an unreadable provenance as
     * verified.
     */
    public static AnswerConfidence parseOrUnknown(String token) {
        if (token == null || token.isBlank()) return UNKNOWN;
        try {
            return valueOf(token.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }
}
