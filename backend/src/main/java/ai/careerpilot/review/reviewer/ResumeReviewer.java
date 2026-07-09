package ai.careerpilot.review.reviewer;

import ai.careerpilot.domain.ResumeTailoring;
import ai.careerpilot.review.ReviewContext;
import ai.careerpilot.review.ReviewSection;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase 7.12 — reviews the resume/tailoring already produced by Resume Intelligence + Resume Tailoring
 * (Phase 2D). Pure and stateless: it NEVER regenerates or edits a resume, it only scores completeness,
 * tailoring effectiveness (ATS lift), and confidence from the persisted {@link ResumeTailoring}.
 * Flag-gated by {@code application.review.resume.enabled}.
 */
@Component
public class ResumeReviewer {

    public static final String NAME = "resume";

    private final boolean enabled;

    public ResumeReviewer(@Value("${application.review.resume.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public ReviewSection review(ReviewContext ctx) {
        return evaluate(ctx.tailoring());
    }

    /** Pure scoring of the tailored resume — side-effect free, unit-testable. */
    public ReviewSection evaluate(ResumeTailoring t) {
        List<String> reasons = new ArrayList<>();
        List<String> suggestions = new ArrayList<>();
        if (t == null) {
            reasons.add("No tailored resume version exists for this application.");
            suggestions.add("Run resume tailoring before submitting.");
            return ReviewSection.of(NAME, 0, reasons, suggestions);
        }
        int atsAfter = clamp(t.getAtsAfter());
        int score = atsAfter;
        reasons.add("Tailored resume v1." + t.getTailoringVersion() + " with post-tailoring ATS " + atsAfter + ".");

        if (t.getImprovementScore() != null && t.getImprovementScore() > 0) {
            score = Math.min(100, score + 5);
            reasons.add("Tailoring improved ATS by " + t.getImprovementScore() + " points.");
        } else {
            suggestions.add("Tailoring showed little ATS improvement — consider re-tailoring against the JD.");
        }
        double confidence = t.getConfidenceScore() == null ? 0 : t.getConfidenceScore().doubleValue();
        if (confidence > 0 && confidence < 0.5) {
            score = Math.max(0, score - 5);
            reasons.add("Low tailoring confidence (" + fmt(t.getConfidenceScore()) + ").");
        }
        if (atsAfter < 70) {
            suggestions.add("Post-tailoring ATS below 70 — strengthen keyword coverage and achievements.");
        }
        return ReviewSection.of(NAME, clamp(score), reasons, suggestions);
    }

    private static int clamp(Integer v) {
        if (v == null) return 0;
        return Math.max(0, Math.min(100, v));
    }

    private static String fmt(BigDecimal v) {
        return v == null ? "0" : v.toPlainString();
    }
}
