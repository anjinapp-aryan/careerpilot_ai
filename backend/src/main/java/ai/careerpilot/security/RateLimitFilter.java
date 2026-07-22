package ai.careerpilot.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Phase 8.1 — enforces the {@code security.rate-limit.auth-per-minute} / {@code api-per-minute}
 * values that {@code application.yml} has declared since the original scaffold but that nothing
 * ever read (confirmed by architecture review: no Bucket4j/RedisRateLimiter anywhere in the
 * codebase). Deliberately NOT Redis-backed — this is a single-VM deployment (one backend
 * container on the Oracle Cloud VM), so an in-memory per-IP fixed-window counter is sufficient
 * and adds no new infrastructure dependency; if the backend is ever horizontally scaled, this
 * would need a shared store (Redis is already provisioned and would be the natural upgrade path).
 *
 * <p>Gated {@code security.rate-limit.enabled} (default {@code false}) — dark-shipped, matching
 * every other capability in this codebase. Diagnostics and Actuator endpoints are exempt so
 * existing monitoring/polling behavior never regresses. Client IP is read from
 * {@code X-Forwarded-For} first (Nginx sets this — see {@code deployment/nginx/careerpilot.conf})
 * and falls back to {@code getRemoteAddr()} for direct/local access.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final long WINDOW_MILLIS = 60_000;
    private static final long IDLE_EVICTION_MILLIS = 5 * 60_000;

    private final boolean enabled;
    private final int authPerMinute;
    private final int apiPerMinute;
    private final ConcurrentHashMap<String, Bucket> authBuckets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Bucket> apiBuckets = new ConcurrentHashMap<>();

    public RateLimitFilter(@Value("${security.rate-limit.enabled:false}") boolean enabled,
                           @Value("${security.rate-limit.auth-per-minute:10}") int authPerMinute,
                           @Value("${security.rate-limit.api-per-minute:120}") int apiPerMinute) {
        this.enabled = enabled;
        this.authPerMinute = authPerMinute;
        this.apiPerMinute = apiPerMinute;
    }

    public boolean isEnabled() {
        return enabled;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!enabled) {
            chain.doFilter(request, response);
            return;
        }
        String path = request.getRequestURI();
        if (path.startsWith("/api/diagnostics") || path.startsWith("/actuator")) {
            chain.doFilter(request, response);
            return;
        }

        boolean isAuth = path.startsWith("/api/auth/");
        ConcurrentHashMap<String, Bucket> buckets = isAuth ? authBuckets : apiBuckets;
        int limit = isAuth ? authPerMinute : apiPerMinute;
        String key = clientIp(request);

        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket());
        if (!bucket.tryConsume(limit)) {
            log.warn("Rate limit exceeded ip={} path={}", key, path);
            response.setStatus(429);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"rate limit exceeded, try again shortly\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    /** Evicts idle buckets so the map doesn't grow unbounded under a wide spread of source IPs. */
    @Scheduled(fixedDelay = 600_000)
    void evictIdleBuckets() {
        long cutoff = Instant.now().toEpochMilli() - IDLE_EVICTION_MILLIS;
        authBuckets.entrySet().removeIf(e -> e.getValue().windowStart < cutoff);
        apiBuckets.entrySet().removeIf(e -> e.getValue().windowStart < cutoff);
    }

    private static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /** Fixed-window counter — simple, not a true sliding window, adequate for brute-force protection. */
    static final class Bucket {
        private volatile long windowStart = Instant.now().toEpochMilli();
        private final AtomicInteger count = new AtomicInteger(0);

        synchronized boolean tryConsume(int limit) {
            long now = Instant.now().toEpochMilli();
            if (now - windowStart >= WINDOW_MILLIS) {
                windowStart = now;
                count.set(0);
            }
            return count.incrementAndGet() <= limit;
        }
    }
}
