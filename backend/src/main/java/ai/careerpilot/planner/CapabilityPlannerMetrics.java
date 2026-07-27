package ai.careerpilot.planner;

/**
 * Phase 11.2 — observability for the planning layer. Not explicitly named in the phase spec's
 * "Introduce" list for 11.2, but the overall Phase 11 Observability section requires "Planner
 * latency" — added here rather than overloading {@code ai.careerpilot.capability.CapabilityMetrics}
 * (Phase 10.3), since planning and capability execution are different concerns measured at
 * different layers.
 */
public interface CapabilityPlannerMetrics {

    void recordPlanLatency(long latencyMs);

    void recordPlanSize(int stepCount);

    void recordCycleDetected();
}
