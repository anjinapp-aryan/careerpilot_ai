package ai.careerpilot.review.reviewer;

import ai.careerpilot.domain.ResumeAtsAnalysis;
import ai.careerpilot.review.ReviewContext;
import ai.careerpilot.review.ReviewSection;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 7.12 — reviews the persisted {@link ResumeAtsAnalysis} (Phase 2D.2). Pure and stateless: it
 * NEVER re-runs ATS optimization, it only reports the existing score, missing-keyword pressure, and the
 * analysis's own suggestions. Flag-gated by {@code application.review.ats.enabled}.
 */
@Component
public class AtsReviewer {

    public static final String NAME = "ats";

    private final boolean enabled;

    public AtsReviewer(@Value("${application.review.ats.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public ReviewSection review(ReviewContext ctx) {
        return evaluate(ctx.ats());
    }

    /** Pure scoring of the ATS analysis — side-effect free, unit-testable. */
    public ReviewSection evaluate(ResumeAtsAnalysis ats) {
        List<String> reasons = new ArrayList<>();
        List<String> suggestions = new ArrayList<>();
        if (ats == null) {
            reasons.add("No ATS analysis is available for this application.");
            suggestions.add("Run ATS optimization before submitting.");
            return ReviewSection.of(NAME, 0, reasons, suggestions);
        }
        int score = clamp(ats.getAtsScore());
        reasons.add("ATS score " + score + ".");

        int missing = count(ats.getMissingKeywords());
        if (missing > 0) {
            reasons.add(missing + " missing keyword(s) detected.");
            if (missing >= 5) {
                score = Math.max(0, score - 5);
                suggestions.add("Address the top missing keywords to lift ATS parseability.");
            }
        }
        if (ats.getSuggestions() != null && !ats.getSuggestions().isBlank()) {
            suggestions.add("ATS analysis suggestions available for review.");
        }
        if (score < 70) {
            suggestions.add("ATS below 70 — improve section structure and keyword coverage.");
        }
        return ReviewSection.of(NAME, score, reasons, suggestions);
    }

    private static int clamp(Integer v) {
        if (v == null) return 0;
        return Math.max(0, Math.min(100, v));
    }

    /** Count comma/newline-separated keyword tokens without assuming a specific serialization. */
    private static int count(String csv) {
        if (csv == null || csv.isBlank()) return 0;
        int n = 0;
        for (String s : csv.split("[,\\n]")) {
            if (!s.trim().isEmpty()) n++;
        }
        return n;
    }
}
