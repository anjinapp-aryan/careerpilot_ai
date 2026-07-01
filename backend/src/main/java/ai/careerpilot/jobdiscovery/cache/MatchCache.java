package ai.careerpilot.jobdiscovery.cache;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Phase 2B-3 (Step 11) — activates the provisioned-but-unused Redis to skip
 * {@link ai.careerpilot.jobdiscovery.JobMatchingService#refreshForUser} (rescoring the pool +
 * upserting/deleting {@code job_recommendations}) when nothing has changed since the last refresh.
 *
 * <p>Key = {@code userId + resumeVersion + jobPoolVersion}: {@code resumeVersion} is the candidate's
 * current {@code resumeId} (from {@code CandidateMatchSignals}, changes on any new resume upload or
 * profile rebuild), {@code jobPoolVersion} is {@link ai.careerpilot.repo.JobRepository#maxDiscoveredCreatedAt()}
 * (changes whenever new jobs are discovered). Either changing invalidates the cache implicitly —
 * there is no explicit eviction, only a version mismatch on the next read. TTL 24h as a hard upper
 * bound on staleness even if a change is somehow missed.
 *
 * <p>Purely a performance layer: {@code job_recommendations} in Postgres remains the source of
 * truth for what the API returns; a cache miss just means the existing refresh path runs exactly
 * as it does today. Flag-gated ({@code jobs.matching.cache-enabled}, default off) — off means every
 * call is a miss, i.e. today's behavior, byte-for-byte.
 */
@Component
public class MatchCache {

    private static final Duration TTL = Duration.ofHours(24);
    private static final String KEY_PREFIX = "match:refresh:";

    private final StringRedisTemplate redis;
    private final MatchCacheMetrics metrics;
    private final boolean enabled;

    public MatchCache(StringRedisTemplate redis, MatchCacheMetrics metrics,
                      @Value("${jobs.matching.cache-enabled:false}") boolean enabled) {
        this.redis = redis;
        this.metrics = metrics;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * True when a refresh already ran for this exact (user, resume, pool) version within the TTL.
     * Always false when disabled or Redis is unreachable — a cache failure degrades to "always
     * refresh" (today's behavior), never to a stale/incorrect read.
     */
    public boolean isFresh(UUID userId, UUID resumeVersion, Instant poolVersion) {
        if (!enabled) return false;
        try {
            String cached = redis.opsForValue().get(key(userId));
            boolean fresh = cached != null && cached.equals(versionToken(resumeVersion, poolVersion));
            if (fresh) metrics.recordHit(); else metrics.recordMiss();
            return fresh;
        } catch (Exception e) {
            metrics.recordMiss();
            return false; // Redis down → fail open to "always refresh", not to a stale read.
        }
    }

    /** Record that a refresh just completed for this (user, resume, pool) version. */
    public void markRefreshed(UUID userId, UUID resumeVersion, Instant poolVersion) {
        if (!enabled) return;
        try {
            redis.opsForValue().set(key(userId), versionToken(resumeVersion, poolVersion), TTL);
        } catch (Exception e) {
            // Best-effort: a failed cache write just means the next call misses too. Never propagate.
        }
    }

    private static String key(UUID userId) {
        return KEY_PREFIX + userId;
    }

    private static String versionToken(UUID resumeVersion, Instant poolVersion) {
        return (resumeVersion == null ? "none" : resumeVersion.toString()) + ":"
                + (poolVersion == null ? "none" : poolVersion.toString());
    }
}
