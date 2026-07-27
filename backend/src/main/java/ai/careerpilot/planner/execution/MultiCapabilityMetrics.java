package ai.careerpilot.planner.execution;

/**
 * Phase 11.3 — observability for capability execution: per-capability execution time, retry
 * counts, parallel-stage size, partial-failure counts, and overall plan latency — matching the
 * Phase 11 Observability section's "Capability execution" and "Parallel execution" line items.
 */
public interface MultiCapabilityMetrics {

    void recordCapabilityExecutionTime(String capabilityType, long latencyMs);

    void recordRetry(String capabilityType, int attempt);

    void recordPartialFailure(String capabilityType);

    void recordStageSize(int stepCount);

    void recordPlanExecutionLatency(long latencyMs);
}
