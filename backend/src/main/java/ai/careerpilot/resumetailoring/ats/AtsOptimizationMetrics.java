package ai.careerpilot.resumetailoring.ats;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 2D.2 — in-memory counters for the ATS Optimization engine, mirroring {@code
 * ResumeTailoringCacheMetrics}: counts + latency + per-provider usage. Exposed via the diagnostics
 * endpoint — no resume/job content, counts only.
 */
@Component
public class AtsOptimizationMetrics {

    private final AtomicLong total = new AtomicLong();
    private final AtomicLong success = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong latencySumMs = new AtomicLong();
    private final AtomicLong latencyCount = new AtomicLong();
    private final Map<String, AtomicLong> providerUsage = new ConcurrentHashMap<>();

    public void recordRequest() { total.incrementAndGet(); }
    public void recordSuccess() { success.incrementAndGet(); }
    public void recordFailure() { failures.incrementAndGet(); }

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
        out.put("atsOptimizationTotal", total.get());
        out.put("atsOptimizationSuccess", success.get());
        out.put("atsOptimizationFailures", failures.get());
        long count = latencyCount.get();
        out.put("atsOptimizationAvgLatencyMs", count == 0 ? 0 : latencySumMs.get() / count);
        Map<String, Long> providers = new LinkedHashMap<>();
        providerUsage.forEach((k, v) -> providers.put(k, v.get()));
        out.put("atsOptimizationProviderUsage", providers);
        return out;
    }
}
