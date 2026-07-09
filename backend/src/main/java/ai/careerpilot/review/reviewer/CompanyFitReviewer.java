package ai.careerpilot.review.reviewer;

import ai.careerpilot.domain.JobRecommendation;
import ai.careerpilot.review.ReviewContext;
import ai.careerpilot.review.ReviewSection;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 7.12 — reviews company/role fit from the persisted {@link JobRecommendation} (Phase 2B/2C) and
 * the Phase 7.8 company-research availability. Pure and stateless: it NEVER re-scores the recommendation
 * or re-runs company research, it only reports skill/experience alignment already computed.
 * Flag-gated by {@code application.review.company.enabled}.
 */
@Component
public class CompanyFitReviewer {

    public static final String NAME = "company_fit";

    private final boolean enabled;

    public CompanyFitReviewer(@Value("${application.review.company.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public ReviewSection review(ReviewContext ctx) {
        return evaluate(ctx.recommendation(), ctx.companyResearchAvailable());
    }

    /** Pure scoring of company/role fit — side-effect free, unit-testable. */
    public ReviewSection evaluate(JobRecommendation rec, boolean companyResearchAvailable) {
        List<String> reasons = new ArrayList<>();
        List<String> suggestions = new ArrayList<>();
        if (rec == null) {
            reasons.add("No recommendation exists for this job — fit cannot be assessed.");
            suggestions.add("Run recommendation matching before review.");
            return ReviewSection.of(NAME, 0, reasons, suggestions);
        }
        int score = clamp(rec.getMatchScore());
        reasons.add("Match score " + score + " (confidence " + nz(rec.getConfidenceLevel()) + ").");

        int matching = count(rec.getMatchingSkills());
        int missing = count(rec.getMissingSkills());
        if (matching > 0) reasons.add(matching + " matching skill(s).");
        if (missing > 0) {
            reasons.add(missing + " missing skill(s) for this role.");
            if (missing >= 4) {
                score = Math.max(0, score - 5);
                suggestions.add("Several role skills are unmet — confirm the fit is genuine before applying.");
            }
        }
        if (companyResearchAvailable) {
            score = Math.min(100, score + 3);
            reasons.add("Company research snapshot available to support fit.");
        } else {
            suggestions.add("No company research bound — enable it to strengthen the fit signal.");
        }
        return ReviewSection.of(NAME, clamp(score), reasons, suggestions);
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(100, v));
    }

    private static String nz(String s) {
        return s == null ? "UNKNOWN" : s;
    }

    private static int count(String csv) {
        if (csv == null || csv.isBlank()) return 0;
        int n = 0;
        for (String s : csv.split("[,\\n]")) {
            if (!s.trim().isEmpty()) n++;
        }
        return n;
    }
}
