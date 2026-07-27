package ai.careerpilot.memory.enterprise;

/**
 * Phase 11.4 — observability for the enterprise memory layer, matching the Phase 11
 * Observability section's "Memory usage" line item.
 */
public interface MemoryMetrics {

    void recordRemember(String type);

    void recordForget(String type);

    void recordRetrieval(String type, long latencyMs);

    void recordSearch(long latencyMs, int resultCount);

    void recordConsolidation(int promoted, int evicted);
}
