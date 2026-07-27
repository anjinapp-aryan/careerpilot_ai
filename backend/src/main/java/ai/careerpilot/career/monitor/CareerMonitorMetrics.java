package ai.careerpilot.career.monitor;

/**
 * Phase 11.5 — observability for the proactive intelligence layer: alerts detected per type,
 * how many were suppressed by the {@link CareerTimeline} cooldown, and monitor-run latency.
 */
public interface CareerMonitorMetrics {

    void recordAlertDetected(String type);

    void recordAlertSuppressed(String type);

    void recordMonitorRunLatency(long latencyMs);
}
