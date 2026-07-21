package ai.careerpilot.memory;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Phase 7.15.1 — in-memory counters, same shape as {@code InterviewMetrics}. */
@Component
public class CareerMemoryMetrics {

    private final AtomicLong extractionAttempts = new AtomicLong();
    private final AtomicLong extractionSuccesses = new AtomicLong();
    private final AtomicLong extractionFailures = new AtomicLong();
    private final AtomicLong retrievalCount = new AtomicLong();
    private final AtomicLong retrievalLatencyTotalNanos = new AtomicLong();

    public void recordExtractionAttempt() { extractionAttempts.incrementAndGet(); }
    public void recordExtractionSuccess() { extractionSuccesses.incrementAndGet(); }
    public void recordExtractionFailure() { extractionFailures.incrementAndGet(); }

    public void recordRetrieval(long durationNanos) {
        retrievalCount.incrementAndGet();
        retrievalLatencyTotalNanos.addAndGet(durationNanos);
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("extractionAttempts", extractionAttempts.get());
        out.put("extractionSuccesses", extractionSuccesses.get());
        out.put("extractionFailures", extractionFailures.get());
        long count = retrievalCount.get();
        out.put("retrievalCount", count);
        out.put("avgRetrievalLatencyMs", count == 0 ? 0.0
                : (retrievalLatencyTotalNanos.get() / (double) count) / 1_000_000.0);
        return out;
    }
}
