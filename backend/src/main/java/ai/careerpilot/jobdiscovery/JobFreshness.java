package ai.careerpilot.jobdiscovery;

import java.time.Duration;
import java.time.Instant;

/**
 * Global Job Discovery Expansion — freshness banding over the existing {@code postedDate}/
 * {@code createdAt} fields (no new persistence, purely a display/ranking classification computed
 * on read). Pure and deterministic.
 */
public enum JobFreshness {
    VERY_FRESH,
    FRESH,
    RECENT,
    AGING,
    STALE;

    /**
     * Classifies from whichever timestamp is available — {@code postedDate} when the source gave
     * one, else the discovery {@code createdAt}. Returns {@code null} (not a guess) when neither
     * timestamp exists.
     */
    public static JobFreshness classify(Instant postedDate, Instant createdAt, Instant now) {
        Instant reference = postedDate != null ? postedDate : createdAt;
        if (reference == null || now == null) return null;
        long hours = Duration.between(reference, now).toHours();
        if (hours < 0) hours = 0; // clock skew / future-dated posting — treat as just-posted
        if (hours <= 24) return VERY_FRESH;
        if (hours <= 72) return FRESH;
        if (hours <= 168) return RECENT;
        if (hours <= 336) return AGING;
        return STALE;
    }
}
