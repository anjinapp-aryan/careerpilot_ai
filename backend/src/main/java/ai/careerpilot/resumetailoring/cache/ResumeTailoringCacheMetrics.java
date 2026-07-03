package ai.careerpilot.resumetailoring.cache;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 2D.1 (Step 13) — in-memory counters for the tailoring engine, mirroring
 * {@code MatchCacheMetrics}. Exposed via the diagnostics endpoint — counts + latency only.
 *
 * <p>Phase 2D.1.1 adds {@link #recordProviderUsed} — a small per-provider tally fed by {@code
 * AiGatewayService.getLastUsedProvider()} right after each tailoring LLM call, so the diagnostics
 * endpoint gives direct, visible evidence of which provider is actually serving tailoring calls
 * (confirming the {@code resume.tailoring.preferred-providers} override takes effect, rather than
 * only trusting the config value).
 */
@Component
public class ResumeTailoringCacheMetrics {

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

    /** Tally which provider actually served a tailoring LLM call. {@code null}/blank is ignored (e.g. cache hits). */
    public void recordProviderUsed(String provider) {
        if (provider == null || provider.isBlank()) return;
        providerUsage.computeIfAbsent(provider, p -> new AtomicLong()).incrementAndGet();
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("resumeTailoringTotal", total.get());
        out.put("resumeTailoringSuccess", success.get());
        out.put("resumeTailoringFailures", failures.get());
        out.put("resumeTailoringCacheHits", cacheHits.get());
        out.put("resumeTailoringCacheMisses", cacheMisses.get());
        long count = latencyCount.get();
        out.put("resumeTailoringAvgLatencyMs", count == 0 ? 0 : latencySumMs.get() / count);
        Map<String, Long> providers = new LinkedHashMap<>();
        providerUsage.forEach((k, v) -> providers.put(k, v.get()));
        out.put("resumeTailoringProviderUsage", providers);
        return out;
    }
}
