package ai.careerpilot.resumetailoring.coverletter;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 2D.5 — in-memory counters for the Cover Letter engine (LLM-backed, so per-provider usage
 * is tracked like {@code ResumeTailoringCacheMetrics}). Exposed via
 * {@code /api/diagnostics/cover-letter}.
 */
@Component
public class CoverLetterMetrics {

    private final AtomicLong total = new AtomicLong();
    private final AtomicLong success = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong cacheHits = new AtomicLong();
    private final AtomicLong cacheMisses = new AtomicLong();
    private final AtomicLong latencySumMs = new AtomicLong();
    private final AtomicLong latencyCount = new AtomicLong();
    private final Map<String, AtomicLong> providerUsage = new ConcurrentHashMap<>();

    public void recordRequest() { total.incrementAndGet(); }
    public void recordSuccess() { success.incrementAndGet(); }
    public void recordFailure() { failures.incrementAndGet(); }
    public void recordCacheHit() { cacheHits.incrementAndGet(); }
    public void recordCacheMiss() { cacheMisses.incrementAndGet(); }

    public void recordLatency(long ms) {
        latencySumMs.addAndGet(ms);
        latencyCount.incrementAndGet();
    }

    public void recordProviderUsed(String provider) {
        if (provider == null || provider.isBlank()) return;
        providerUsage.computeIfAbsent(provider, p -> new AtomicLong()).incrementAndGet();
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("coverLetterTotal", total.get());
        out.put("coverLetterSuccess", success.get());
        out.put("coverLetterFailures", failures.get());
        out.put("coverLetterCacheHits", cacheHits.get());
        out.put("coverLetterCacheMisses", cacheMisses.get());
        long count = latencyCount.get();
        out.put("coverLetterAvgLatencyMs", count == 0 ? 0 : latencySumMs.get() / count);
        Map<String, Long> providers = new LinkedHashMap<>();
        providerUsage.forEach((k, v) -> providers.put(k, v.get()));
        out.put("coverLetterProviderUsage", providers);
        return out;
    }
}
