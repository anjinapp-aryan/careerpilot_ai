package ai.careerpilot.planner;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 11.2 — the default {@link CapabilityPlannerMetrics}, same hand-rolled {@link AtomicLong}
 * style as every other metrics implementation in this codebase.
 */
public class InMemoryCapabilityPlannerMetrics implements CapabilityPlannerMetrics {

    private final AtomicLong latencySumMs = new AtomicLong();
    private final AtomicLong latencyCount = new AtomicLong();
    private final AtomicLong planSizeSum = new AtomicLong();
    private final AtomicLong planCount = new AtomicLong();
    private final AtomicLong cycleDetections = new AtomicLong();

    @Override
    public void recordPlanLatency(long latencyMs) {
        latencySumMs.addAndGet(latencyMs);
        latencyCount.incrementAndGet();
    }

    @Override
    public void recordPlanSize(int stepCount) {
        planSizeSum.addAndGet(stepCount);
        planCount.incrementAndGet();
    }

    @Override
    public void recordCycleDetected() {
        cycleDetections.incrementAndGet();
    }

    public long avgLatencyMs() {
        long count = latencyCount.get();
        return count == 0 ? 0 : latencySumMs.get() / count;
    }

    public double avgPlanSize() {
        long count = planCount.get();
        return count == 0 ? 0.0 : (double) planSizeSum.get() / count;
    }

    public long cycleDetectionCount() {
        return cycleDetections.get();
    }
}
