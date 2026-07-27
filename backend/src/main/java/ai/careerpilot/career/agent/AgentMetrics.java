package ai.careerpilot.career.agent;

/**
 * Phase 11.6 — observability for the autonomous agent: run counts, per-task-type outcomes,
 * skipped-due-to-cadence counts, and run latency — matching the Phase 11 Observability
 * section's "Agent execution" and "Reflection" line items.
 */
public interface AgentMetrics {

    void recordRun();

    void recordTaskOutcome(String taskType, String outcome);

    void recordSkippedRun(String reason);

    void recordRunLatency(long latencyMs);
}
