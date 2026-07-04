package ai.careerpilot.resumetailoring.explain;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 2D.4 — in-memory counters for the ATS Explainability engine (deterministic, non-LLM).
 * Exposed via {@code /api/diagnostics/ats-explainability}.
 */
@Component
public class AtsExplainabilityMetrics {

    private final AtomicLong total = new AtomicLong();
    private final AtomicLong success = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong latencySumMs = new AtomicLong();
    private final AtomicLong latencyCount = new AtomicLong();

    public void recordRequest() { total.incrementAndGet(); }
    public void recordSuccess() { success.incrementAndGet(); }
    public void recordFailure() { failures.incrementAndGet(); }

    public void recordLatency(long ms) {
        latencySumMs.addAndGet(ms);
        latencyCount.incrementAndGet();
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("atsExplainabilityTotal", total.get());
        out.put("atsExplainabilitySuccess", success.get());
        out.put("atsExplainabilityFailures", failures.get());
        long count = latencyCount.get();
        out.put("atsExplainabilityAvgLatencyMs", count == 0 ? 0 : latencySumMs.get() / count);
        return out;
    }
}
