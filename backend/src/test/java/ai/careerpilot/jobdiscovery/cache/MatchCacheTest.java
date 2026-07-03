package ai.careerpilot.jobdiscovery.cache;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Phase 2B-3 — {@link MatchCache} is pure infrastructure: it must never turn a version change into
 * a false "fresh" (stale-read risk), must fail OPEN (treat as a miss) when Redis errors, and must be
 * a complete no-op when disabled — matching every other Phase 2B flag's "off = today's behavior"
 * contract.
 */
class MatchCacheTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID resumeV1 = UUID.randomUUID();
    private final UUID resumeV2 = UUID.randomUUID();
    private final Instant poolV1 = Instant.parse("2026-01-01T00:00:00Z");
    private final Instant poolV2 = Instant.parse("2026-01-02T00:00:00Z");

    private StringRedisTemplate mockRedisWith(ValueOperations<String, String> ops) {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenReturn(ops);
        return redis;
    }

    @Test
    void disabledIsAlwaysAMissAndNeverTouchesRedis() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        MatchCache cache = new MatchCache(redis, new MatchCacheMetrics(), false);

        assertFalse(cache.isFresh(userId, resumeV1, poolV1));
        cache.markRefreshed(userId, resumeV1, poolV1);

        verifyNoInteractions(redis);
    }

    @Test
    @SuppressWarnings("unchecked")
    void freshAfterMarkingTheSameVersion() {
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        StringRedisTemplate redis = mockRedisWith(ops);
        String[] stored = new String[1];
        doAnswer(inv -> { stored[0] = inv.getArgument(1); return null; })
                .when(ops).set(anyString(), anyString(), any(Duration.class));
        when(ops.get(anyString())).thenAnswer(inv -> stored[0]);

        MatchCache cache = new MatchCache(redis, new MatchCacheMetrics(), true);
        assertFalse(cache.isFresh(userId, resumeV1, poolV1)); // nothing marked yet
        cache.markRefreshed(userId, resumeV1, poolV1);
        assertTrue(cache.isFresh(userId, resumeV1, poolV1));
    }

    @Test
    @SuppressWarnings("unchecked")
    void resumeVersionChangeInvalidatesTheCache() {
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        StringRedisTemplate redis = mockRedisWith(ops);
        String[] stored = new String[1];
        doAnswer(inv -> { stored[0] = inv.getArgument(1); return null; })
                .when(ops).set(anyString(), anyString(), any(Duration.class));
        when(ops.get(anyString())).thenAnswer(inv -> stored[0]);

        MatchCache cache = new MatchCache(redis, new MatchCacheMetrics(), true);
        cache.markRefreshed(userId, resumeV1, poolV1);

        assertFalse(cache.isFresh(userId, resumeV2, poolV1));  // new resume → stale
        assertFalse(cache.isFresh(userId, resumeV1, poolV2));  // new pool → stale
    }

    @Test
    @SuppressWarnings("unchecked")
    void redisErrorOnReadFailsOpenToAMiss() {
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        StringRedisTemplate redis = mockRedisWith(ops);
        when(ops.get(anyString())).thenThrow(new RuntimeException("connection refused"));

        MatchCache cache = new MatchCache(redis, new MatchCacheMetrics(), true);
        assertFalse(cache.isFresh(userId, resumeV1, poolV1));
    }

    @Test
    @SuppressWarnings("unchecked")
    void redisErrorOnWriteNeverThrows() {
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        StringRedisTemplate redis = mockRedisWith(ops);
        doThrow(new RuntimeException("connection refused"))
                .when(ops).set(anyString(), anyString(), any(Duration.class));

        MatchCache cache = new MatchCache(redis, new MatchCacheMetrics(), true);
        assertDoesNotThrow(() -> cache.markRefreshed(userId, resumeV1, poolV1));
    }
}
