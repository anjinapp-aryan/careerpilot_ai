package ai.careerpilot.domain;

import java.util.Locale;

/**
 * Phase C — where a candidate profile value came from, and therefore whether automation may type it
 * into a real employer's form.
 *
 * <p><b>This enum is the enforcement point for "never fabricate".</b> Phase 12C put that rule in
 * {@code AnswerResolver} by restricting it to three audited sources; Phase C generalises it, because
 * a profile field can now be populated several ways and only some of them represent something the
 * candidate actually asserted.
 *
 * <p>{@link #AI_SUGGESTED} is the whole reason the distinction exists. A model can plausibly infer a
 * phone number's format, a likely job title, or a graduation year — and every one of those would be
 * a fabrication delivered to an employer under the candidate's real name. Suggestions are stored so
 * a human can review and accept them; accepting one re-records it as {@link #HUMAN_CONFIRMED}, and
 * only then does it become usable.
 */
public enum FieldVerificationSource {

    /** Typed by the candidate. The strongest signal available. */
    USER_ENTERED(true),

    /**
     * Extracted from the candidate's own uploaded résumé. Trusted: the résumé is a document the
     * candidate authored and already sends to employers, so a value read out of it is their own
     * assertion rather than a model's invention.
     */
    RESUME_EXTRACTED(true),

    /** Imported from a profile the candidate connected themselves. */
    LINKEDIN_IMPORTED(true),

    /**
     * Proposed by a model and not yet reviewed. <b>Never usable by automation.</b> Held so it can be
     * shown to the candidate for confirmation.
     */
    AI_SUGGESTED(false),

    /** A suggestion a human explicitly reviewed and accepted. */
    HUMAN_CONFIRMED(true);

    private final boolean trustedForAutomation;

    FieldVerificationSource(boolean trustedForAutomation) {
        this.trustedForAutomation = trustedForAutomation;
    }

    /** Whether a value from this source may be entered into a live employer form without review. */
    public boolean isTrustedForAutomation() {
        return trustedForAutomation;
    }

    /**
     * Parse a stored provenance token. An unrecognised or missing token is <b>not</b> trusted —
     * failing closed, because the alternative is treating an unknown provenance as verified, which
     * defeats the entire purpose of recording it.
     */
    public static FieldVerificationSource parseOrUntrusted(String token) {
        if (token == null || token.isBlank()) return AI_SUGGESTED;
        try {
            return valueOf(token.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return AI_SUGGESTED;
        }
    }
}
