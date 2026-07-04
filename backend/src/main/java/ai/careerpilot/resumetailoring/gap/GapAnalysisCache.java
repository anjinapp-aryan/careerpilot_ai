package ai.careerpilot.resumetailoring.gap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 2D.3 — Redis-backed cache mapping (user, job) at a specific (tailoring, ATS analysis)
 * version pair to the already-computed {@code ResumeGapAnalysis} row id. Same versioned-key,
 * 7-day-TTL, fail-OPEN-on-Redis-error contract as {@code ResumeTailoringCache}: a miss (including
 * "Redis unreachable") just means "recompute" — never a stale read. Since gap analysis is
 * deterministic, a recompute is always safe, just wasted work.
 */
@Component
public class GapAnalysisCache {

    private static final Duration TTL = Duration.ofDays(7);
    private static final String KEY_PREFIX = "gap-analysis:";

    private final StringRedisTemplate redis;
    private final GapAnalysisMetrics metrics;
    private final boolean enabled;

    public GapAnalysisCache(StringRedisTemplate redis, GapAnalysisMetrics metrics,
                            @Value("${gap.analysis.cache-enabled:true}") boolean enabled) {
        this.redis = redis;
        this.metrics = metrics;
        this.enabled = enabled;
    }

    public Optional<UUID> get(UUID userId, UUID jobId, UUID tailoringId, UUID atsAnalysisId) {
        if (!enabled) return Optional.empty();
        try {
            String cached = redis.opsForValue().get(key(userId, jobId));
            String token = token(tailoringId, atsAnalysisId);
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

    public void put(UUID userId, UUID jobId, UUID tailoringId, UUID atsAnalysisId, UUID gapAnalysisId) {
        if (!enabled) return;
        try {
            redis.opsForValue().set(key(userId, jobId), token(tailoringId, atsAnalysisId) + "|" + gapAnalysisId, TTL);
        } catch (Exception e) {
            // Best-effort; a failed write just means the next call recomputes. Never propagate.
        }
    }

    private static String key(UUID userId, UUID jobId) {
        return KEY_PREFIX + userId + ":" + jobId;
    }

    private static String token(UUID tailoringId, UUID atsAnalysisId) {
        return tailoringId + ":" + (atsAnalysisId == null ? "none" : atsAnalysisId.toString());
    }
}
