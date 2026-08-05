package ai.careerpilot.employerquestion;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Phase D — the answer to one employer question, together with the complete account of why it was
 * selected.
 *
 * <p><b>Explainability is a field, not a log line.</b> A reviewer approving an application needs to
 * see, at the moment of approval, where each answer came from and when a human last vouched for it.
 * Reconstructing that afterwards from logs is not the same thing, and an answer nobody can explain
 * is one nobody should submit.
 *
 * <p>{@link #usable()} is the single question automation asks. It is deliberately the conjunction of
 * <em>approved</em> and <em>trusted confidence</em>: a draft at {@code AI_SUGGESTED} fails both, and
 * an unapproved answer fails even at {@code PROFILE_DERIVED}, because approval is a human act that
 * confidence alone cannot stand in for.
 *
 * @param answerText   the answer, or {@code null} when none is usable — never a placeholder
 * @param confidence   provenance band
 * @param approved     whether a human explicitly approved this exact text
 * @param approvedAt   when, or {@code null}
 * @param source       where the value came from
 * @param reason       why this answer was selected, in a sentence a human can read
 * @param questionId   the canonical question matched, or {@code null} when none was
 */
public record AnswerResolution(
        String answerText,
        AnswerConfidence confidence,
        boolean approved,
        Instant approvedAt,
        String source,
        String reason,
        UUID questionId) {

    /** No usable answer, with the reason stated. Never an empty string masquerading as an answer. */
    public static AnswerResolution none(String reason) {
        return new AnswerResolution(null, AnswerConfidence.UNKNOWN, false, null, "none", reason, null);
    }

    /**
     * Whether browser automation may use this. Both conditions are required — see the class note.
     */
    public boolean usable() {
        return approved && confidence.isUsableByAutomation()
                && answerText != null && !answerText.isBlank();
    }

    /** Explainability payload. Contains the answer, so it is only ever returned to its own user. */
    public Map<String, Object> explain() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("answer", answerText);
        out.put("confidence", confidence.name());
        out.put("confidenceUsableByAutomation", confidence.isUsableByAutomation());
        out.put("approved", approved);
        out.put("approvedAt", approvedAt == null ? null : approvedAt.toString());
        out.put("source", source);
        out.put("reason", reason);
        out.put("usableByAutomation", usable());
        out.put("questionId", questionId == null ? null : questionId.toString());
        return out;
    }
}
