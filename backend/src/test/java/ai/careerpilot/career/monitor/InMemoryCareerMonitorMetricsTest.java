package ai.careerpilot.career.monitor;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryCareerMonitorMetricsTest {

    private final InMemoryCareerMonitorMetrics metrics = new InMemoryCareerMonitorMetrics();

    @Test
    void tracksDetectedAndSuppressedCountsPerType() {
        metrics.recordAlertDetected("JOB_MATCH");
        metrics.recordAlertDetected("JOB_MATCH");
        metrics.recordAlertSuppressed("JOB_MATCH");

        assertThat(metrics.detectedCount("JOB_MATCH")).isEqualTo(2);
        assertThat(metrics.suppressedCount("JOB_MATCH")).isEqualTo(1);
    }

    @Test
    void computesAverageRunLatency() {
        metrics.recordMonitorRunLatency(10);
        metrics.recordMonitorRunLatency(20);

        assertThat(metrics.avgRunLatencyMs()).isEqualTo(15);
    }
}
