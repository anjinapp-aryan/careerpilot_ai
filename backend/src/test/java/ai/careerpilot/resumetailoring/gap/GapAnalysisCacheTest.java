package ai.careerpilot.resumetailoring.gap;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Phase 2D.3 — {@link GapAnalysisCache} must never turn a version change into a false hit and
 * must fail OPEN (miss) on Redis errors, mirroring {@code ResumeTailoringCacheTest}'s contract.
 */
class GapAnalysisCacheTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();
    private final UUID tailoringId = UUID.randomUUID();
    private final UUID atsAnalysisId = UUID.randomUUID();
    private final UUID gapAnalysisId = UUID.randomUUID();

    @SuppressWarnings("unchecked")
    private StringRedisTemplate redisReturning(String value) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenReturn(value);
        return redis;
    }

    @Test
    void hitWhenVersionTokenMatches() {
        String stored = tailoringId + ":" + atsAnalysisId + "|" + gapAnalysisId;
        GapAnalysisCache cache = new GapAnalysisCache(redisReturning(stored), new GapAnalysisMetrics(), true);

        Optional<UUID> hit = cache.get(userId, jobId, tailoringId, atsAnalysisId);

        assertEquals(Optional.of(gapAnalysisId), hit);
    }

    @Test
    void missWhenTailoringVersionChanged() {
        String stored = UUID.randomUUID() + ":" + atsAnalysisId + "|" + gapAnalysisId;
        GapAnalysisCache cache = new GapAnalysisCache(redisReturning(stored), new GapAnalysisMetrics(), true);

        assertTrue(cache.get(userId, jobId, tailoringId, atsAnalysisId).isEmpty());
    }

    @Test
    void failsOpenOnRedisError() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenThrow(new RuntimeException("redis down"));
        GapAnalysisCache cache = new GapAnalysisCache(redis, new GapAnalysisMetrics(), true);

        assertTrue(cache.get(userId, jobId, tailoringId, atsAnalysisId).isEmpty());
        assertDoesNotThrow(() -> cache.put(userId, jobId, tailoringId, atsAnalysisId, gapAnalysisId));
    }

    @Test
    void disabledIsANoOp() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        GapAnalysisCache cache = new GapAnalysisCache(redis, new GapAnalysisMetrics(), false);

        assertTrue(cache.get(userId, jobId, tailoringId, atsAnalysisId).isEmpty());
        cache.put(userId, jobId, tailoringId, atsAnalysisId, gapAnalysisId);
        verifyNoInteractions(redis);
    }
}
