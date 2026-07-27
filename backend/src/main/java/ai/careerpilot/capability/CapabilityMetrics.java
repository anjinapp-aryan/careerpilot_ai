package ai.careerpilot.capability;

/**
 * Phase 10.3 — observability contract for the capability orchestration layer. Named metrics
 * match the phase spec's Observability list: capability selection counts, capability decision
 * latency, tool execution time, merged context size, and fallback reasons.
 */
public interface CapabilityMetrics {

    void recordCapabilitySelected(String capabilityType);

    void recordCapabilityLatency(String capabilityType, long latencyMs);

    void recordToolExecutionTime(String toolName, long latencyMs);

    void recordMergedContextSize(int characters);

    void recordFallback(String reason);

    /**
     * Phase 10.4 — total time a caller (e.g. {@code CopilotService}) spent going through the
     * capability-routing decision, independent of whether tool calling was actually used.
     */
    void recordEndToEndLatency(long latencyMs);
}
