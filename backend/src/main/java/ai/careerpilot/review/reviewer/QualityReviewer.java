package ai.careerpilot.review.reviewer;

import ai.careerpilot.review.ConsistencyStatus;
import ai.careerpilot.review.QualityCategory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Phase 7.12 — computes overall application quality (0-100 + category) from the other reviewers'
 * already-computed scores plus the consistency verdict. Pure and stateless: it aggregates, it does not
 * re-review anything. A FAIL consistency forces the {@code BLOCKED} category regardless of scores, so a
 * contradictory package can never present as high quality. Flag-gated by
 * {@code application.review.quality.enabled}.
 */
@Component
public class QualityReviewer {

    public static final String NAME = "quality";

    private final boolean enabled;

    public QualityReviewer(@Value("${application.review.quality.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** The aggregate score and its band. */
    public record QualityResult(int score, QualityCategory category) {}

    /**
     * Weighted mean of the present reviewer scores (each nullable when its reviewer is off), banded into
     * a category. Weights: resume 30, ATS 25, company fit 25, learning 20. Consistency FAIL ⇒ BLOCKED.
     */
    public QualityResult evaluate(Integer resumeScore, Integer atsScore, Integer companyFitScore,
                                  Integer learningConfidence, ConsistencyStatus consistency) {
        double weighted = weighted(resumeScore, 30) + weighted(atsScore, 25)
                + weighted(companyFitScore, 25) + weighted(learningConfidence, 20);
        double totalWeight = weight(resumeScore, 30) + weight(atsScore, 25)
                + weight(companyFitScore, 25) + weight(learningConfidence, 20);

        int score = totalWeight == 0 ? 0 : (int) Math.round(weighted / totalWeight);

        if (consistency == ConsistencyStatus.FAIL) {
            return new QualityResult(score, QualityCategory.BLOCKED);
        }
        return new QualityResult(score, QualityCategory.fromScore(score));
    }

    private static double weighted(Integer value, double weight) {
        return value == null ? 0 : value * weight;
    }

    private static double weight(Integer value, double weight) {
        return value == null ? 0 : weight;
    }
}
