package ai.careerpilot.planner.execution;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 11.3 — the default {@link MultiCapabilityMetrics}, same hand-rolled {@link AtomicLong}
 * counter style as every other metrics implementation in this codebase.
 */
public class InMemoryMultiCapabilityMetrics implements MultiCapabilityMetrics {

    private final Map<String, AtomicLong> executionTimeMs = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> retries = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> partialFailures = new ConcurrentHashMap<>();
    private final AtomicLong stageSizeSum = new AtomicLong();
    private final AtomicLong stageSamples = new AtomicLong();
    private final AtomicLong planLatencySumMs = new AtomicLong();
    private final AtomicLong planSamples = new AtomicLong();

    @Override
    public void recordCapabilityExecutionTime(String capabilityType, long latencyMs) {
        executionTimeMs.computeIfAbsent(capabilityType, k -> new AtomicLong()).addAndGet(latencyMs);
    }

    @Override
    public void recordRetry(String capabilityType, int attempt) {
        retries.computeIfAbsent(capabilityType, k -> new AtomicLong()).incrementAndGet();
    }

    @Override
    public void recordPartialFailure(String capabilityType) {
        partialFailures.computeIfAbsent(capabilityType, k -> new AtomicLong()).incrementAndGet();
    }

    @Override
    public void recordStageSize(int stepCount) {
        stageSizeSum.addAndGet(stepCount);
        stageSamples.incrementAndGet();
    }

    @Override
    public void recordPlanExecutionLatency(long latencyMs) {
        planLatencySumMs.addAndGet(latencyMs);
        planSamples.incrementAndGet();
    }

    public long executionTimeMs(String capabilityType) {
        return executionTimeMs.getOrDefault(capabilityType, new AtomicLong()).get();
    }

    public long retryCount(String capabilityType) {
        return retries.getOrDefault(capabilityType, new AtomicLong()).get();
    }

    public long partialFailureCount(String capabilityType) {
        return partialFailures.getOrDefault(capabilityType, new AtomicLong()).get();
    }

    public double avgStageSize() {
        long samples = stageSamples.get();
        return samples == 0 ? 0.0 : (double) stageSizeSum.get() / samples;
    }

    public long avgPlanLatencyMs() {
        long samples = planSamples.get();
        return samples == 0 ? 0 : planLatencySumMs.get() / samples;
    }
}
