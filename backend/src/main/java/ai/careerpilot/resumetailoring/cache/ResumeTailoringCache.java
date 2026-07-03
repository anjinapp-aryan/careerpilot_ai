package ai.careerpilot.resumetailoring.cache;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 2D.1 (Step 9) — Redis-backed cache mapping (user, resume version, job version, profile
 * version) to the already-generated {@code ResumeTailoring} row id, so re-requesting a tailored
 * resume for an unchanged (resume, job, profile) triple returns the existing generation instead
 * of calling the LLM again. Mirrors {@code MatchCache}'s versioned-key, fail-open-on-Redis-error
 * philosophy exactly: a cache miss (including "Redis unreachable") just means "generate again,"
 * never "serve something stale or wrong."
 */
@Component
public class ResumeTailoringCache {

    private static final Duration TTL = Duration.ofDays(7);
    private static final String KEY_PREFIX = "resume-tailor:";

    private final StringRedisTemplate redis;
    private final ResumeTailoringCacheMetrics metrics;
    private final boolean enabled;

    public ResumeTailoringCache(StringRedisTemplate redis, ResumeTailoringCacheMetrics metrics,
                                @Value("${resume.tailoring.cache-enabled:false}") boolean enabled) {
        this.redis = redis;
        this.metrics = metrics;
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** The cached tailoring id for this exact (user, job, resume, profile) version, if fresh. */
    public Optional<UUID> get(UUID userId, UUID jobId, Instant resumeVersion, Instant jobVersion, UUID profileVersion) {
        if (!enabled) return Optional.empty();
        try {
            String cached = redis.opsForValue().get(key(userId, jobId));
            String token = versionToken(resumeVersion, jobVersion, profileVersion);
            if (cached != null && cached.startsWith(token + "|")) {
                metrics.recordCacheHit();
                return Optional.of(UUID.fromString(cached.substring(token.length() + 1)));
            }
            metrics.recordCacheMiss();
            return Optional.empty();
        } catch (Exception e) {
            metrics.recordCacheMiss();
            return Optional.empty(); // Redis down -> fail open to "always regenerate", never a stale read.
        }
    }

    public void put(UUID userId, UUID jobId, Instant resumeVersion, Instant jobVersion, UUID profileVersion,
                    UUID resumeTailoringId) {
        if (!enabled) return;
        try {
            String token = versionToken(resumeVersion, jobVersion, profileVersion);
            redis.opsForValue().set(key(userId, jobId), token + "|" + resumeTailoringId, TTL);
        } catch (Exception e) {
            // Best-effort: a failed cache write just means the next call misses too. Never propagate.
        }
    }

    private static String key(UUID userId, UUID jobId) {
        return KEY_PREFIX + userId + ":" + jobId;
    }

    private static String versionToken(Instant resumeVersion, Instant jobVersion, UUID profileVersion) {
        return (resumeVersion == null ? "none" : resumeVersion.toString()) + ":"
                + (jobVersion == null ? "none" : jobVersion.toString()) + ":"
                + (profileVersion == null ? "none" : profileVersion.toString());
    }
}
