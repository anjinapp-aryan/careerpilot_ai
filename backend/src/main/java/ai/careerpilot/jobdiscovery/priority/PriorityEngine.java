package ai.careerpilot.jobdiscovery.priority;

import ai.careerpilot.domain.Job;
import ai.careerpilot.jobdiscovery.JobScoring;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Phase 2C-1 (Step 4) — computes a recommendation's <i>priority</i>: the already-computed match
 * score plus additive bonuses for actionable attributes (visa sponsorship, remote, an exact role
 * match, a strong salary fit, a recent posting). The result is a number that can exceed 100 and a
 * {@link PriorityLevel} band.
 *
 * <p>This is a <b>separate ranking dimension</b>, not a change to scoring: it never touches
 * {@link JobScoring} and it never re-orders the existing Recommended list (which stays ordered by
 * match score). It exists so a future "priority" view/tab can surface the most <i>actionable</i>
 * matches, and it is deliberately allowed to double-count signals already in the score (visa is only
 * 1% of the score, but a visa-sponsoring role is disproportionately actionable for a candidate who
 * needs sponsorship).
 *
 * <p>Flag-gated by {@code recommendation.priority.enabled} (default off) — when off,
 * {@link ai.careerpilot.jobdiscovery.JobMatchingService} leaves priority null and behavior is
 * byte-for-byte identical to today.
 */
@Component
public class PriorityEngine {

    // ── Attribute bonuses (per Step 4). Applied additively on top of the base match score. ──
    static final int VISA_BONUS = 10;
    static final int REMOTE_BONUS = 5;
    static final int EXACT_ROLE_BONUS = 15;
    static final int SALARY_BONUS = 5;
    static final int RECENT_BONUS = 5;

    /** A role sub-score at/above this is treated as an "exact" role match for the bonus. */
    static final int EXACT_ROLE_MIN = 90;
    /** A salary sub-score at/above this is treated as a strong salary fit for the bonus. */
    static final int STRONG_SALARY_MIN = 90;
    /** A posting newer than this many days counts as "recent" for the bonus. */
    static final int RECENT_DAYS = 7;

    // ── Priority bands over the (base + bonuses) total, which ranges 0..~140. ──
    static final int CRITICAL_MIN = 115;
    static final int HIGH_MIN = 100;
    static final int MEDIUM_MIN = 80;

    private final boolean enabled;

    public PriorityEngine(@Value("${jobs.recommendation.priority-enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** The priority number and its band for one scored job. */
    public record PriorityResult(int priorityScore, PriorityLevel level) {}

    /** Compute priority from the job's attributes and its already-computed score breakdown. */
    public PriorityResult compute(Job job, JobScoring.ScoreResultV2 result) {
        int score = result.matchScore();
        JobScoring.ScoreBreakdown b = result.breakdown();

        if (Boolean.TRUE.equals(job.getSponsorshipAvailable())) score += VISA_BONUS;
        if (isRemote(job)) score += REMOTE_BONUS;
        if (b.role() >= EXACT_ROLE_MIN) score += EXACT_ROLE_BONUS;
        if (b.salary() >= STRONG_SALARY_MIN) score += SALARY_BONUS;
        if (isRecent(job)) score += RECENT_BONUS;

        return new PriorityResult(score, level(score));
    }

    private static boolean isRemote(Job job) {
        return "REMOTE".equals(job.getRemoteType()) || Boolean.TRUE.equals(job.getRemote());
    }

    private static boolean isRecent(Job job) {
        Instant posted = job.getPostedDate();
        return posted != null && Duration.between(posted, Instant.now()).toDays() <= RECENT_DAYS;
    }

    private static PriorityLevel level(int score) {
        if (score >= CRITICAL_MIN) return PriorityLevel.CRITICAL;
        if (score >= HIGH_MIN) return PriorityLevel.HIGH;
        if (score >= MEDIUM_MIN) return PriorityLevel.MEDIUM;
        return PriorityLevel.LOW;
    }
}
