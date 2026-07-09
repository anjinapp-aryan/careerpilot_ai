package ai.careerpilot.execution.browser;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 2E.2 — in-memory counters for the browser-automation layer. In the 2E build every attempt
 * is a stub-rejection, so {@code stubRejections} tracks how often the inert provider was reached.
 */
@Component
public class BrowserAutomationMetrics {

    private final AtomicLong total = new AtomicLong();
    private final AtomicLong stubRejections = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong latencySumMs = new AtomicLong();
    private final AtomicLong latencyCount = new AtomicLong();

    public void recordRequest() { total.incrementAndGet(); }
    public void recordStubRejection() { stubRejections.incrementAndGet(); }
    public void recordFailure() { failures.incrementAndGet(); }

    public void recordLatency(long ms) {
        latencySumMs.addAndGet(ms);
        latencyCount.incrementAndGet();
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("browserTotal", total.get());
        out.put("browserStubRejections", stubRejections.get());
        out.put("browserFailures", failures.get());
        long count = latencyCount.get();
        out.put("browserAvgLatencyMs", count == 0 ? 0 : latencySumMs.get() / count);
        return out;
    }
}
