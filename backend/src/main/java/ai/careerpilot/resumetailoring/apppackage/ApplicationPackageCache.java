package ai.careerpilot.resumetailoring.apppackage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 2D.6 — Redis-backed cache mapping (user, job) at a specific (tailoring, cover letter)
 * artifact pair to the already-assembled {@code ApplicationPackage} head row id. Same
 * versioned-key, 7-day-TTL, fail-OPEN contract as the other pipeline caches; assembly is
 * deterministic so a recompute is always safe.
 */
@Component
public class ApplicationPackageCache {

    private static final Duration TTL = Duration.ofDays(7);
    private static final String KEY_PREFIX = "app-package:";

    private final StringRedisTemplate redis;
    private final ApplicationPackageMetrics metrics;
    private final boolean enabled;

    public ApplicationPackageCache(StringRedisTemplate redis, ApplicationPackageMetrics metrics,
                                   @Value("${application.package.cache-enabled:true}") boolean enabled) {
        this.redis = redis;
        this.metrics = metrics;
        this.enabled = enabled;
    }

    public Optional<UUID> get(UUID userId, UUID jobId, UUID resumeTailoringId, UUID coverLetterId) {
        if (!enabled) return Optional.empty();
        try {
            String cached = redis.opsForValue().get(key(userId, jobId));
            String token = token(resumeTailoringId, coverLetterId);
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

    public void put(UUID userId, UUID jobId, UUID resumeTailoringId, UUID coverLetterId, UUID packageId) {
        if (!enabled) return;
        try {
            redis.opsForValue().set(key(userId, jobId), token(resumeTailoringId, coverLetterId) + "|" + packageId, TTL);
        } catch (Exception e) {
            // Best-effort; fail open.
        }
    }

    private static String key(UUID userId, UUID jobId) {
        return KEY_PREFIX + userId + ":" + jobId;
    }

    private static String token(UUID resumeTailoringId, UUID coverLetterId) {
        return (resumeTailoringId == null ? "none" : resumeTailoringId.toString()) + ":"
                + (coverLetterId == null ? "none" : coverLetterId.toString());
    }
}
