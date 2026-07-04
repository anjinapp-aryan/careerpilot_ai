package ai.careerpilot.execution.execution;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 2E.1 — in-memory counters for the Application Execution Engine, including a per-terminal
 * tally (submitted / aborted / failed / retry) so the diagnostics endpoint shows the outcome
 * distribution without exposing any application content or PII.
 */
@Component
public class ApplicationExecutionMetrics {

    private final AtomicLong total = new AtomicLong();
    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong aborted = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong retries = new AtomicLong();
    private final AtomicLong latencySumMs = new AtomicLong();
    private final AtomicLong latencyCount = new AtomicLong();

    public void recordRequest() { total.incrementAndGet(); }
    public void recordSubmitted() { submitted.incrementAndGet(); }
    public void recordAborted() { aborted.incrementAndGet(); }
    public void recordFailure() { failures.incrementAndGet(); }
    public void recordRetry() { retries.incrementAndGet(); }

    public void recordLatency(long ms) {
        latencySumMs.addAndGet(ms);
        latencyCount.incrementAndGet();
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("applicationExecutionTotal", total.get());
        out.put("applicationExecutionSubmitted", submitted.get());
        out.put("applicationExecutionAborted", aborted.get());
        out.put("applicationExecutionFailures", failures.get());
        out.put("applicationExecutionRetries", retries.get());
        long count = latencyCount.get();
        out.put("applicationExecutionAvgLatencyMs", count == 0 ? 0 : latencySumMs.get() / count);
        return out;
    }
}
