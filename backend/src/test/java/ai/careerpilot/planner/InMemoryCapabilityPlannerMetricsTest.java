package ai.careerpilot.planner;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryCapabilityPlannerMetricsTest {

    private final InMemoryCapabilityPlannerMetrics metrics = new InMemoryCapabilityPlannerMetrics();

    @Test
    void computesAverageLatency() {
        metrics.recordPlanLatency(10);
        metrics.recordPlanLatency(20);
        assertThat(metrics.avgLatencyMs()).isEqualTo(15);
    }

    @Test
    void computesAveragePlanSize() {
        metrics.recordPlanSize(1);
        metrics.recordPlanSize(3);
        assertThat(metrics.avgPlanSize()).isEqualTo(2.0);
    }

    @Test
    void tracksCycleDetections() {
        assertThat(metrics.cycleDetectionCount()).isZero();
        metrics.recordCycleDetected();
        assertThat(metrics.cycleDetectionCount()).isEqualTo(1);
    }
}
