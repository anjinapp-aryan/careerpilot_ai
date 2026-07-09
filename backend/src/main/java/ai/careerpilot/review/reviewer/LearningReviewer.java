package ai.careerpilot.review.reviewer;

import ai.careerpilot.domain.FailurePattern;
import ai.careerpilot.domain.ResumeLearning;
import ai.careerpilot.domain.SuccessPattern;
import ai.careerpilot.learning.api.LearningExplainContextService.LearningExplainContext;
import ai.careerpilot.review.ReviewContext;
import ai.careerpilot.review.ReviewSection;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase 7.12 — reviews historical effectiveness from the persisted Phase 6 learning state (success /
 * failure patterns, best resume version) via the read-only {@code LearningExplainContext}. Pure and
 * stateless: it NEVER writes a learning event or mutates a pattern, it only reports a confidence signal.
 * Flag-gated by {@code application.review.learning.enabled}.
 */
@Component
public class LearningReviewer {

    public static final String NAME = "learning";

    private final boolean enabled;

    public LearningReviewer(@Value("${application.review.learning.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public ReviewSection review(ReviewContext ctx) {
        return evaluate(ctx.learning());
    }

    /** Pure derivation of learning confidence (0-100) — side-effect free, unit-testable. */
    public ReviewSection evaluate(LearningExplainContext learning) {
        List<String> reasons = new ArrayList<>();
        List<String> suggestions = new ArrayList<>();
        if (learning == null || learning.totalEvents() == 0) {
            reasons.add("No learning history yet — neutral confidence applied.");
            return ReviewSection.of(NAME, 50, reasons, suggestions);
        }
        reasons.add(learning.totalEvents() + " learning event(s) on record.");

        double successAvg = avg(learning.topSuccessPatterns().stream().map(SuccessPattern::getSuccessRate).toList());
        double failureAvg = avg(learning.topFailurePatterns().stream().map(FailurePattern::getFailureRate).toList());
        double confidence = 50 + successAvg * 0.4 - failureAvg * 0.3;

        if (successAvg > 0) reasons.add("Historical success rate ~" + pct(successAvg) + " across top patterns.");
        if (failureAvg > 0) reasons.add("Historical failure rate ~" + pct(failureAvg) + " across top patterns.");

        ResumeLearning best = learning.bestResumeVersion();
        if (best != null) {
            double interview = rate(best.getInterviewRate());
            double offer = rate(best.getOfferRate());
            confidence += Math.min(10, (interview + offer) * 0.05);
            reasons.add("Best resume version: interview ~" + pct(interview) + ", offer ~" + pct(offer) + ".");
        } else {
            suggestions.add("No best-performing resume version identified yet.");
        }
        if (failureAvg >= 60) {
            suggestions.add("High historical failure at this target — verify before auto-applying.");
        }
        return ReviewSection.of(NAME, clamp(confidence), reasons, suggestions);
    }

    private static double avg(List<BigDecimal> rates) {
        if (rates == null || rates.isEmpty()) return 0;
        double sum = 0;
        int n = 0;
        for (BigDecimal r : rates) {
            if (r == null) continue;
            sum += r.doubleValue() * 100;
            n++;
        }
        return n == 0 ? 0 : sum / n;
    }

    private static double rate(BigDecimal v) {
        return v == null ? 0 : v.doubleValue() * 100;
    }

    private static String pct(double v) {
        return Math.round(v) + "%";
    }

    private static int clamp(double v) {
        return (int) Math.max(0, Math.min(100, Math.round(v)));
    }
}
