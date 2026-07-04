package ai.careerpilot.resumetailoring.gap;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 2D.3 — in-memory counters for the Gap Analysis engine (deterministic, non-LLM — no
 * provider usage to track). Exposed via {@code /api/diagnostics/gap-analysis}.
 */
@Component
public class GapAnalysisMetrics {

    private final AtomicLong total = new AtomicLong();
    private final AtomicLong success = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong cacheHits = new AtomicLong();
    private final AtomicLong cacheMisses = new AtomicLong();
    private final AtomicLong latencySumMs = new AtomicLong();
    private final AtomicLong latencyCount = new AtomicLong();

    public void recordRequest() { total.incrementAndGet(); }
    public void recordSuccess() { success.incrementAndGet(); }
    public void recordFailure() { failures.incrementAndGet(); }
    public void recordCacheHit() { cacheHits.incrementAndGet(); }
    public void recordCacheMiss() { cacheMisses.incrementAndGet(); }

    public void recordLatency(long ms) {
        latencySumMs.addAndGet(ms);
        latencyCount.incrementAndGet();
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("gapAnalysisTotal", total.get());
        out.put("gapAnalysisSuccess", success.get());
        out.put("gapAnalysisFailures", failures.get());
        out.put("gapAnalysisCacheHits", cacheHits.get());
        out.put("gapAnalysisCacheMisses", cacheMisses.get());
        long count = latencyCount.get();
        out.put("gapAnalysisAvgLatencyMs", count == 0 ? 0 : latencySumMs.get() / count);
        return out;
    }
}
