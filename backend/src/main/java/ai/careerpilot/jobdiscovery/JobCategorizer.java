package ai.careerpilot.jobdiscovery;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Phase 2B-1 (Step 7) — buckets a recommendation's <i>already-computed</i> {@link JobScoring}
 * match score into an action {@link JobCategory}. This is a pure function of the score; it never
 * re-scores and never calls an LLM, so it adds no cost and cannot change which jobs are matched.
 *
 * <p>Flag-gated by {@code jobs.recommendation.auto-categorization-enabled} (default off). When off,
 * {@link JobMatchingService} leaves {@code category} null and behavior is byte-for-byte identical to
 * today — the column is purely additive.
 *
 * <p>Thresholds (Phase 2C-2, Step 2): {@code AUTO_APPLY_READY >= 95}, {@code HIGH_PRIORITY 90–94},
 * {@code HUMAN_REVIEW 80–89}, {@code RECOMMENDED 70–79}, else {@code ARCHIVED}.
 */
@Component
public class JobCategorizer {

    static final int AUTO_APPLY_READY_MIN = 95;
    static final int HIGH_PRIORITY_MIN = 90;
    static final int HUMAN_REVIEW_MIN = 80;
    static final int RECOMMENDED_MIN = 70;

    private final boolean enabled;

    public JobCategorizer(@Value("${jobs.recommendation.auto-categorization-enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Map a 0–100 match score to its action category. */
    public JobCategory categorize(int matchScore) {
        if (matchScore >= AUTO_APPLY_READY_MIN) return JobCategory.AUTO_APPLY_READY;
        if (matchScore >= HIGH_PRIORITY_MIN) return JobCategory.HIGH_PRIORITY;
        if (matchScore >= HUMAN_REVIEW_MIN) return JobCategory.HUMAN_REVIEW;
        if (matchScore >= RECOMMENDED_MIN) return JobCategory.RECOMMENDED;
        return JobCategory.ARCHIVED;
    }
}
