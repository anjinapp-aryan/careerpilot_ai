package ai.careerpilot.intent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 11.1 — the default {@link IntentMetrics} implementation. Plain in-memory {@link
 * AtomicLong} counters, the same hand-rolled style as {@code ai.careerpilot.ai.AiMetrics}, {@code
 * ai.careerpilot.mcp.InMemoryMcpMetrics}, and {@code ai.careerpilot.capability.InMemoryCapabilityMetrics}.
 */
public class InMemoryIntentMetrics implements IntentMetrics {

    private final Map<String, AtomicLong> selections = new ConcurrentHashMap<>();
    private final AtomicLong latencySumMs = new AtomicLong();
    private final AtomicLong latencyCount = new AtomicLong();
    private final AtomicLong confidenceSum = new AtomicLong();
    private final AtomicLong confidenceCount = new AtomicLong();
    private final Map<String, AtomicLong> fallbackReasons = new ConcurrentHashMap<>();

    @Override
    public void recordIntentSelected(String intentType) {
        selections.computeIfAbsent(intentType, k -> new AtomicLong()).incrementAndGet();
    }

    @Override
    public void recordIntentLatency(long latencyMs) {
        latencySumMs.addAndGet(latencyMs);
        latencyCount.incrementAndGet();
    }

    @Override
    public void recordConfidence(double score) {
        // stored *1000 to keep integer AtomicLong math exact enough for averaging
        confidenceSum.addAndGet(Math.round(score * 1000));
        confidenceCount.incrementAndGet();
    }

    @Override
    public void recordFallback(String reason) {
        fallbackReasons.computeIfAbsent(reason, k -> new AtomicLong()).incrementAndGet();
    }

    public long selectionCount(String intentType) {
        return selections.getOrDefault(intentType, new AtomicLong()).get();
    }

    public long avgLatencyMs() {
        long count = latencyCount.get();
        return count == 0 ? 0 : latencySumMs.get() / count;
    }

    public double avgConfidence() {
        long count = confidenceCount.get();
        return count == 0 ? 0.0 : (confidenceSum.get() / 1000.0) / count;
    }

    public long fallbackCount(String reason) {
        return fallbackReasons.getOrDefault(reason, new AtomicLong()).get();
    }
}
