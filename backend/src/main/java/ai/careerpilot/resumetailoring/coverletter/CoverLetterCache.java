package ai.careerpilot.resumetailoring.coverletter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 2D.5 — Redis-backed cache mapping (user, job) at a specific tailoring version to the
 * already-generated {@code CoverLetter} head row id, so an unchanged tailored resume never
 * re-burns an LLM call. Same versioned-key, 7-day-TTL, fail-OPEN contract as
 * {@code ResumeTailoringCache}.
 */
@Component
public class CoverLetterCache {

    private static final Duration TTL = Duration.ofDays(7);
    private static final String KEY_PREFIX = "cover-letter:";

    private final StringRedisTemplate redis;
    private final CoverLetterMetrics metrics;
    private final boolean enabled;

    public CoverLetterCache(StringRedisTemplate redis, CoverLetterMetrics metrics,
                            @Value("${cover.letter.cache-enabled:true}") boolean enabled) {
        this.redis = redis;
        this.metrics = metrics;
        this.enabled = enabled;
    }

    public Optional<UUID> get(UUID userId, UUID jobId, UUID resumeTailoringId) {
        if (!enabled) return Optional.empty();
        try {
            String cached = redis.opsForValue().get(key(userId, jobId));
            String token = String.valueOf(resumeTailoringId);
            if (cached != null && cached.startsWith(token + "|")) {
                metrics.recordCacheHit();
                return Optional.of(UUID.fromString(cached.substring(token.length() + 1)));
            }
            metrics.recordCacheMiss();
            return Optional.empty();
        } catch (Exception e) {
            metrics.recordCacheMiss();
            return Optional.empty();
        }
    }

    public void put(UUID userId, UUID jobId, UUID resumeTailoringId, UUID coverLetterId) {
        if (!enabled) return;
        try {
            redis.opsForValue().set(key(userId, jobId), resumeTailoringId + "|" + coverLetterId, TTL);
        } catch (Exception e) {
            // Best-effort; fail open.
        }
    }

    private static String key(UUID userId, UUID jobId) {
        return KEY_PREFIX + userId + ":" + jobId;
    }
}
