package ai.careerpilot.execution.retry;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Phase 2E.6 — in-memory counters for the retry & recovery engine, tallied per action. */
@Component
public class RetryMetrics {

    private final AtomicLong total = new AtomicLong();
    private final AtomicLong retries = new AtomicLong();
    private final AtomicLong pauses = new AtomicLong();
    private final AtomicLong stops = new AtomicLong();
    private final AtomicLong exhausted = new AtomicLong();

    public void recordDecision() { total.incrementAndGet(); }
    public void recordRetry() { retries.incrementAndGet(); }
    public void recordPause() { pauses.incrementAndGet(); }
    public void recordStop() { stops.incrementAndGet(); }
    public void recordExhausted() { exhausted.incrementAndGet(); }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("retryTotal", total.get());
        out.put("retryRetries", retries.get());
        out.put("retryPauses", pauses.get());
        out.put("retryStops", stops.get());
        out.put("retryExhausted", exhausted.get());
        return out;
    }
}
