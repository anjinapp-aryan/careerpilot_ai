package ai.careerpilot.jobdiscovery;

import ai.careerpilot.domain.Job;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Phase 2C-2 (Step 8) — the {@code MUST_APPLY} signal: a strict AND of the highest-confidence
 * actionable conditions. Unlike {@link JobCategory} (a single score band), this is a cross-cutting
 * boolean flag — a job can be {@code AUTO_APPLY_READY} by score yet not {@code MUST_APPLY} if it
 * lacks visa support or a recent posting.
 *
 * <p>Computed from the same {@link JobScoring.ScoreResultV2} breakdown the matcher already has, so it
 * adds no cost and no LLM. Bundled with categorization (same {@code JobCategorizer} gate) rather than
 * a new flag. Reject on any missing condition — a MUST_APPLY must be unambiguous.
 */
@Component
public class MustApplyEvaluator {

    /** Role sub-score required (an exact/near-exact role match). */
    static final int ROLE_MIN = 95;
    /** Preference (work-mode) sub-score required. */
    static final int PREFERENCE_MIN = 95;
    /** Posting must be newer than this many days. */
    static final int RECENT_DAYS = 7;

    /** True only when every MUST_APPLY condition holds. Never rejects on a soft/unknown signal. */
    public boolean isMustApply(Job job, JobScoring.ScoreResultV2 result) {
        JobScoring.ScoreBreakdown b = result.breakdown();
        return b.role() >= ROLE_MIN                                    // role match >= 95
                && result.matchedRoleCount() >= 1                      // target role exact
                && b.workMode() >= PREFERENCE_MIN                      // preference >= 95
                && Boolean.TRUE.equals(job.getSponsorshipAvailable())  // visa supported
                && isRecent(job);                                      // recent posting
    }

    private static boolean isRecent(Job job) {
        Instant posted = job.getPostedDate();
        return posted != null && Duration.between(posted, Instant.now()).toDays() <= RECENT_DAYS;
    }
}
