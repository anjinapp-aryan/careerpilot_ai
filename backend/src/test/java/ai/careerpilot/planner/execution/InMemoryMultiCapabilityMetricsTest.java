package ai.careerpilot.planner.execution;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryMultiCapabilityMetricsTest {

    private final InMemoryMultiCapabilityMetrics metrics = new InMemoryMultiCapabilityMetrics();

    @Test
    void tracksExecutionTimePerCapability() {
        metrics.recordCapabilityExecutionTime("GITHUB_REVIEW", 100);
        metrics.recordCapabilityExecutionTime("GITHUB_REVIEW", 50);
        assertThat(metrics.executionTimeMs("GITHUB_REVIEW")).isEqualTo(150);
    }

    @Test
    void tracksRetriesAndPartialFailuresPerCapability() {
        metrics.recordRetry("GITHUB_REVIEW", 1);
        metrics.recordRetry("GITHUB_REVIEW", 2);
        metrics.recordPartialFailure("GITHUB_REVIEW");

        assertThat(metrics.retryCount("GITHUB_REVIEW")).isEqualTo(2);
        assertThat(metrics.partialFailureCount("GITHUB_REVIEW")).isEqualTo(1);
    }

    @Test
    void computesAverageStageSizeAndPlanLatency() {
        metrics.recordStageSize(2);
        metrics.recordStageSize(4);
        metrics.recordPlanExecutionLatency(100);
        metrics.recordPlanExecutionLatency(200);

        assertThat(metrics.avgStageSize()).isEqualTo(3.0);
        assertThat(metrics.avgPlanLatencyMs()).isEqualTo(150);
    }
}
