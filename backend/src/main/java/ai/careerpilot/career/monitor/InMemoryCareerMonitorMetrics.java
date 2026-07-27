package ai.careerpilot.career.monitor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 11.5 — the default {@link CareerMonitorMetrics}, same hand-rolled {@link AtomicLong}
 * counter style as every other metrics implementation in this codebase.
 */
public class InMemoryCareerMonitorMetrics implements CareerMonitorMetrics {

    private final Map<String, AtomicLong> detected = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> suppressed = new ConcurrentHashMap<>();
    private final AtomicLong latencySumMs = new AtomicLong();
    private final AtomicLong runs = new AtomicLong();

    @Override
    public void recordAlertDetected(String type) {
        detected.computeIfAbsent(type, k -> new AtomicLong()).incrementAndGet();
    }

    @Override
    public void recordAlertSuppressed(String type) {
        suppressed.computeIfAbsent(type, k -> new AtomicLong()).incrementAndGet();
    }

    @Override
    public void recordMonitorRunLatency(long latencyMs) {
        latencySumMs.addAndGet(latencyMs);
        runs.incrementAndGet();
    }

    public long detectedCount(String type) {
        return detected.getOrDefault(type, new AtomicLong()).get();
    }

    public long suppressedCount(String type) {
        return suppressed.getOrDefault(type, new AtomicLong()).get();
    }

    public long avgRunLatencyMs() {
        long count = runs.get();
        return count == 0 ? 0 : latencySumMs.get() / count;
    }
}
