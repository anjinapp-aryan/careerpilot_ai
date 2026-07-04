package ai.careerpilot.resumetailoring.apppackage;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 2D.6 — in-memory counters for the Application Package builder (deterministic assembly,
 * no LLM). Exposed via {@code /api/diagnostics/application-package}.
 */
@Component
public class ApplicationPackageMetrics {

    private final AtomicLong total = new AtomicLong();
    private final AtomicLong success = new AtomicLong();
    private final AtomicLong incomplete = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong cacheHits = new AtomicLong();
    private final AtomicLong cacheMisses = new AtomicLong();
    private final AtomicLong latencySumMs = new AtomicLong();
    private final AtomicLong latencyCount = new AtomicLong();

    public void recordRequest() { total.incrementAndGet(); }
    public void recordSuccess() { success.incrementAndGet(); }
    public void recordIncomplete() { incomplete.incrementAndGet(); }
    public void recordFailure() { failures.incrementAndGet(); }
    public void recordCacheHit() { cacheHits.incrementAndGet(); }
    public void recordCacheMiss() { cacheMisses.incrementAndGet(); }

    public void recordLatency(long ms) {
        latencySumMs.addAndGet(ms);
        latencyCount.incrementAndGet();
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("applicationPackageTotal", total.get());
        out.put("applicationPackageSuccess", success.get());
        out.put("applicationPackageIncomplete", incomplete.get());
        out.put("applicationPackageFailures", failures.get());
        out.put("applicationPackageCacheHits", cacheHits.get());
        out.put("applicationPackageCacheMisses", cacheMisses.get());
        long count = latencyCount.get();
        out.put("applicationPackageAvgLatencyMs", count == 0 ? 0 : latencySumMs.get() / count);
        return out;
    }
}
