package ai.careerpilot.resumetailoring.autoapply;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 2D.7 — in-memory counters for Auto Apply preparation (deterministic, no LLM), including
 * a per-verdict tally so the diagnostics endpoint shows the SAFE/REVIEW/MANUAL distribution.
 */
@Component
public class AutoApplyPreparationMetrics {

    private final AtomicLong total = new AtomicLong();
    private final AtomicLong success = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong safeToApply = new AtomicLong();
    private final AtomicLong requiresReview = new AtomicLong();
    private final AtomicLong manualOnly = new AtomicLong();
    private final AtomicLong latencySumMs = new AtomicLong();
    private final AtomicLong latencyCount = new AtomicLong();

    public void recordRequest() { total.incrementAndGet(); }
    public void recordSuccess() { success.incrementAndGet(); }
    public void recordFailure() { failures.incrementAndGet(); }
    public void recordSafeToApply() { safeToApply.incrementAndGet(); }
    public void recordRequiresReview() { requiresReview.incrementAndGet(); }
    public void recordManualOnly() { manualOnly.incrementAndGet(); }

    public void recordLatency(long ms) {
        latencySumMs.addAndGet(ms);
        latencyCount.incrementAndGet();
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("autoApplyTotal", total.get());
        out.put("autoApplySuccess", success.get());
        out.put("autoApplyFailures", failures.get());
        out.put("autoApplySafeToApply", safeToApply.get());
        out.put("autoApplyRequiresReview", requiresReview.get());
        out.put("autoApplyManualOnly", manualOnly.get());
        long count = latencyCount.get();
        out.put("autoApplyAvgLatencyMs", count == 0 ? 0 : latencySumMs.get() / count);
        return out;
    }
}
