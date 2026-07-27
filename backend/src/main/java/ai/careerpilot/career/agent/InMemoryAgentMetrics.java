package ai.careerpilot.career.agent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 11.6 — the default {@link AgentMetrics}, same hand-rolled {@link AtomicLong} counter
 * style as every other metrics implementation in this codebase.
 */
public class InMemoryAgentMetrics implements AgentMetrics {

    private final AtomicLong runs = new AtomicLong();
    private final Map<String, AtomicLong> taskOutcomes = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> skippedRuns = new ConcurrentHashMap<>();
    private final AtomicLong latencySumMs = new AtomicLong();
    private final AtomicLong latencySamples = new AtomicLong();

    @Override
    public void recordRun() {
        runs.incrementAndGet();
    }

    @Override
    public void recordTaskOutcome(String taskType, String outcome) {
        taskOutcomes.computeIfAbsent(taskType + ":" + outcome, k -> new AtomicLong()).incrementAndGet();
    }

    @Override
    public void recordSkippedRun(String reason) {
        skippedRuns.computeIfAbsent(reason, k -> new AtomicLong()).incrementAndGet();
    }

    @Override
    public void recordRunLatency(long latencyMs) {
        latencySumMs.addAndGet(latencyMs);
        latencySamples.incrementAndGet();
    }

    public long runCount() {
        return runs.get();
    }

    public long taskOutcomeCount(String taskType, String outcome) {
        return taskOutcomes.getOrDefault(taskType + ":" + outcome, new AtomicLong()).get();
    }

    public long skippedRunCount(String reason) {
        return skippedRuns.getOrDefault(reason, new AtomicLong()).get();
    }

    public long avgRunLatencyMs() {
        long samples = latencySamples.get();
        return samples == 0 ? 0 : latencySumMs.get() / samples;
    }
}
