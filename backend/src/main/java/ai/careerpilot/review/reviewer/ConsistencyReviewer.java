package ai.careerpilot.review.reviewer;

import ai.careerpilot.review.ConsistencyStatus;
import ai.careerpilot.review.ReviewContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 7.12 — cross-artifact consistency check. Pure and stateless: it compares the presence and
 * internal agreement of the resume / ATS / recommendation / company / package artifacts WITHOUT
 * mutating any of them, and reports PASS / WARNING / FAIL. A hard contradiction or a missing core
 * artifact is a FAIL; softer gaps are WARNING. Flag-gated by {@code application.review.consistency.enabled}.
 */
@Component
public class ConsistencyReviewer {

    public static final String NAME = "consistency";

    private final boolean enabled;

    public ConsistencyReviewer(@Value("${application.review.consistency.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public record ConsistencyResult(ConsistencyStatus status, List<String> reasons) {}

    public ConsistencyResult review(ReviewContext ctx) {
        boolean hasResume = ctx.pkg() != null && ctx.pkg().getResumeId() != null;
        boolean hasTailoring = ctx.tailoring() != null;
        boolean hasAts = ctx.ats() != null;
        boolean hasRecommendation = ctx.recommendation() != null;
        return evaluate(hasResume, hasTailoring, hasAts, hasRecommendation, matchesJob(ctx));
    }

    /** Pure consistency verdict from presence/agreement booleans — side-effect free, unit-testable. */
    public ConsistencyResult evaluate(boolean hasResume, boolean hasTailoring, boolean hasAts,
                                      boolean hasRecommendation, boolean atsMatchesTailoring) {
        List<String> reasons = new ArrayList<>();
        ConsistencyStatus status = ConsistencyStatus.PASS;

        if (!hasResume || !hasRecommendation) {
            status = ConsistencyStatus.FAIL;
            if (!hasResume) reasons.add("Missing resume artifact.");
            if (!hasRecommendation) reasons.add("Missing recommendation artifact.");
            return new ConsistencyResult(status, reasons);
        }
        if (hasAts && !hasTailoring) {
            status = ConsistencyStatus.WARNING;
            reasons.add("ATS analysis present without a tailored resume — artifacts may be out of sync.");
        }
        if (hasAts && hasTailoring && !atsMatchesTailoring) {
            status = ConsistencyStatus.WARNING;
            reasons.add("ATS analysis does not reference the current tailored resume version.");
        }
        if (!hasAts) {
            status = worse(status, ConsistencyStatus.WARNING);
            reasons.add("No ATS analysis to cross-check the resume against.");
        }
        if (reasons.isEmpty()) reasons.add("All core artifacts present and mutually consistent.");
        return new ConsistencyResult(status, reasons);
    }

    private static boolean matchesJob(ReviewContext ctx) {
        if (ctx.ats() == null || ctx.tailoring() == null) return true; // not contradictory when one is absent
        return ctx.ats().getResumeTailoringId() == null
                || ctx.ats().getResumeTailoringId().equals(ctx.tailoring().getId());
    }

    private static ConsistencyStatus worse(ConsistencyStatus a, ConsistencyStatus b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
