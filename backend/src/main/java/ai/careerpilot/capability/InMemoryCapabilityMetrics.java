package ai.careerpilot.capability;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 10.3 — the default {@link CapabilityMetrics} implementation. Plain in-memory {@link
 * AtomicLong} counters, the same hand-rolled style as {@code ai.careerpilot.ai.AiMetrics} and
 * {@code ai.careerpilot.mcp.InMemoryMcpMetrics} — no new dependency.
 */
public class InMemoryCapabilityMetrics implements CapabilityMetrics {

    private final Map<String, AtomicLong> selections = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> latencySumMs = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> latencyCount = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> toolExecutionTimeMs = new ConcurrentHashMap<>();
    private final AtomicLong mergedContextCharsSum = new AtomicLong();
    private final AtomicLong mergedContextSamples = new AtomicLong();
    private final Map<String, AtomicLong> fallbackReasons = new ConcurrentHashMap<>();
    private final AtomicLong endToEndLatencySumMs = new AtomicLong();
    private final AtomicLong endToEndSamples = new AtomicLong();

    @Override
    public void recordCapabilitySelected(String capabilityType) {
        selections.computeIfAbsent(capabilityType, k -> new AtomicLong()).incrementAndGet();
    }

    @Override
    public void recordCapabilityLatency(String capabilityType, long latencyMs) {
        latencySumMs.computeIfAbsent(capabilityType, k -> new AtomicLong()).addAndGet(latencyMs);
        latencyCount.computeIfAbsent(capabilityType, k -> new AtomicLong()).incrementAndGet();
    }

    @Override
    public void recordToolExecutionTime(String toolName, long latencyMs) {
        toolExecutionTimeMs.computeIfAbsent(toolName, k -> new AtomicLong()).addAndGet(latencyMs);
    }

    @Override
    public void recordMergedContextSize(int characters) {
        mergedContextCharsSum.addAndGet(characters);
        mergedContextSamples.incrementAndGet();
    }

    @Override
    public void recordFallback(String reason) {
        fallbackReasons.computeIfAbsent(reason, k -> new AtomicLong()).incrementAndGet();
    }

    @Override
    public void recordEndToEndLatency(long latencyMs) {
        endToEndLatencySumMs.addAndGet(latencyMs);
        endToEndSamples.incrementAndGet();
    }

    public long avgEndToEndLatencyMs() {
        long samples = endToEndSamples.get();
        return samples == 0 ? 0 : endToEndLatencySumMs.get() / samples;
    }

    public long selectionCount(String capabilityType) {
        return selections.getOrDefault(capabilityType, new AtomicLong()).get();
    }

    public long avgCapabilityLatencyMs(String capabilityType) {
        long count = latencyCount.getOrDefault(capabilityType, new AtomicLong()).get();
        return count == 0 ? 0 : latencySumMs.getOrDefault(capabilityType, new AtomicLong()).get() / count;
    }

    public long toolExecutionTimeMs(String toolName) {
        return toolExecutionTimeMs.getOrDefault(toolName, new AtomicLong()).get();
    }

    public long avgMergedContextChars() {
        long samples = mergedContextSamples.get();
        return samples == 0 ? 0 : mergedContextCharsSum.get() / samples;
    }

    public long fallbackCount(String reason) {
        return fallbackReasons.getOrDefault(reason, new AtomicLong()).get();
    }
}
