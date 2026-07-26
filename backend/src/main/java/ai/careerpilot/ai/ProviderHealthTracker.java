package ai.careerpilot.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Tracks provider health to avoid repeatedly calling failed providers.
 * Caches health status for 5 minutes to reduce unnecessary requests.
 */
@Service
public class ProviderHealthTracker {

    private static final Logger log = LoggerFactory.getLogger(ProviderHealthTracker.class);
    private static final long HEALTH_CACHE_MS = 5 * 60 * 1000L; // 5 minutes

    private final Map<String, ProviderHealth> health = new HashMap<>();

    /**
     * Timestamp of each provider's most recent successful call — tracked separately from
     * {@link #health} because that map's 5-minute TTL expires (and a later failure overwrites)
     * the entry, but "when did this provider last actually work" should survive both. Never
     * cleared automatically; only ever moves forward on {@link #recordSuccess}.
     */
    private final Map<String, Instant> lastSuccessAt = new HashMap<>();

    public synchronized void recordSuccess(String providerName) {
        health.put(providerName, new ProviderHealth(Status.HEALTHY, null, Instant.now()));
        lastSuccessAt.put(providerName, Instant.now());
        log.info("Provider {} marked HEALTHY", providerName);
    }

    /** Null if this provider has never recorded a successful call since process start. */
    public synchronized Instant getLastSuccessAt(String providerName) {
        return lastSuccessAt.get(providerName);
    }

    public synchronized void recordFailure(String providerName, String reason) {
        health.put(providerName, new ProviderHealth(Status.DEGRADED, reason, Instant.now()));
        log.warn("Provider {} marked DEGRADED: {}", providerName, reason);
    }

    public synchronized void recordQuotaExceeded(String providerName) {
        health.put(providerName, new ProviderHealth(Status.QUOTA_EXCEEDED, "Quota exhausted", Instant.now()));
        log.warn("Provider {} quota exceeded", providerName);
    }

    public synchronized Status getStatus(String providerName) {
        ProviderHealth h = health.get(providerName);
        if (h == null) return Status.UNKNOWN;
        if (System.currentTimeMillis() - h.recordedAt.toEpochMilli() > HEALTH_CACHE_MS) {
            health.remove(providerName);
            return Status.UNKNOWN;
        }
        return h.status;
    }

    public synchronized String getReason(String providerName) {
        ProviderHealth h = health.get(providerName);
        return h != null ? h.reason : null;
    }

    public synchronized void reset(String providerName) {
        health.remove(providerName);
        log.info("Provider {} health reset", providerName);
    }

    public enum Status {
        HEALTHY, DEGRADED, QUOTA_EXCEEDED, UNKNOWN
    }

    private record ProviderHealth(Status status, String reason, Instant recordedAt) {}
}
