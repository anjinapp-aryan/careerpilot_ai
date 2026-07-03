package ai.careerpilot.resumetailoring.cache;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Phase 2D.1 — {@link ResumeTailoringCache} mirrors {@code MatchCache}'s contract: never turn a
 * version change into a false hit, fail OPEN (miss) on Redis errors, and be a complete no-op when
 * disabled.
 */
class ResumeTailoringCacheTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();
    private final UUID tailoringId = UUID.randomUUID();
    private final UUID profileV1 = UUID.randomUUID();
    private final UUID profileV2 = UUID.randomUUID();
    private final Instant resumeV1 = Instant.parse("2026-01-01T00:00:00Z");
    private final Instant resumeV2 = Instant.parse("2026-01-02T00:00:00Z");
    private final Instant jobV1 = Instant.parse("2026-01-01T00:00:00Z");

    private StringRedisTemplate mockRedisWith(ValueOperations<String, String> ops) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenReturn(ops);
        return redis;
    }

    @Test
    void disabledIsAlwaysAMissAndNeverTouchesRedis() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ResumeTailoringCache cache = new ResumeTailoringCache(redis, new ResumeTailoringCacheMetrics(), false);

        assertTrue(cache.get(userId, jobId, resumeV1, jobV1, profileV1).isEmpty());
        cache.put(userId, jobId, resumeV1, jobV1, profileV1, tailoringId);

        verifyNoInteractions(redis);
    }

    @Test
    @SuppressWarnings("unchecked")
    void hitAfterPuttingTheSameVersionReturnsTheStoredId() {
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        StringRedisTemplate redis = mockRedisWith(ops);
        String[] stored = new String[1];
        doAnswer(inv -> { stored[0] = inv.getArgument(1); return null; })
                .when(ops).set(anyString(), anyString(), any(Duration.class));
        when(ops.get(anyString())).thenAnswer(inv -> stored[0]);

        ResumeTailoringCache cache = new ResumeTailoringCache(redis, new ResumeTailoringCacheMetrics(), true);
        assertTrue(cache.get(userId, jobId, resumeV1, jobV1, profileV1).isEmpty());
        cache.put(userId, jobId, resumeV1, jobV1, profileV1, tailoringId);

        Optional<UUID> hit = cache.get(userId, jobId, resumeV1, jobV1, profileV1);
        assertEquals(tailoringId, hit.orElseThrow());
    }

    @Test
    @SuppressWarnings("unchecked")
    void anyVersionChangeInvalidatesTheCache() {
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        StringRedisTemplate redis = mockRedisWith(ops);
        String[] stored = new String[1];
        doAnswer(inv -> { stored[0] = inv.getArgument(1); return null; })
                .when(ops).set(anyString(), anyString(), any(Duration.class));
        when(ops.get(anyString())).thenAnswer(inv -> stored[0]);

        ResumeTailoringCache cache = new ResumeTailoringCache(redis, new ResumeTailoringCacheMetrics(), true);
        cache.put(userId, jobId, resumeV1, jobV1, profileV1, tailoringId);

        assertTrue(cache.get(userId, jobId, resumeV2, jobV1, profileV1).isEmpty());   // resume changed
        assertTrue(cache.get(userId, jobId, resumeV1, jobV1, profileV2).isEmpty());   // profile changed
    }

    @Test
    @SuppressWarnings("unchecked")
    void redisErrorOnReadFailsOpenToAMiss() {
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        StringRedisTemplate redis = mockRedisWith(ops);
        when(ops.get(anyString())).thenThrow(new RuntimeException("connection refused"));

        ResumeTailoringCache cache = new ResumeTailoringCache(redis, new ResumeTailoringCacheMetrics(), true);
        assertTrue(cache.get(userId, jobId, resumeV1, jobV1, profileV1).isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void redisErrorOnWriteNeverThrows() {
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        StringRedisTemplate redis = mockRedisWith(ops);
        doThrow(new RuntimeException("connection refused"))
                .when(ops).set(anyString(), anyString(), any(Duration.class));

        ResumeTailoringCache cache = new ResumeTailoringCache(redis, new ResumeTailoringCacheMetrics(), true);
        assertDoesNotThrow(() -> cache.put(userId, jobId, resumeV1, jobV1, profileV1, tailoringId));
    }
}
