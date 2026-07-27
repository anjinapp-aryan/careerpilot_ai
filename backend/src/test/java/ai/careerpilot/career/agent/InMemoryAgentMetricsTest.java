package ai.careerpilot.career.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryAgentMetricsTest {

    private final InMemoryAgentMetrics metrics = new InMemoryAgentMetrics();

    @Test
    void tracksRunCount() {
        metrics.recordRun();
        metrics.recordRun();

        assertThat(metrics.runCount()).isEqualTo(2);
    }

    @Test
    void tracksTaskOutcomesPerTypeAndOutcome() {
        metrics.recordTaskOutcome("JOB_DISCOVERY", "DEFERRED");
        metrics.recordTaskOutcome("JOB_DISCOVERY", "DEFERRED");

        assertThat(metrics.taskOutcomeCount("JOB_DISCOVERY", "DEFERRED")).isEqualTo(2);
        assertThat(metrics.taskOutcomeCount("JOB_DISCOVERY", "EXECUTED")).isZero();
    }

    @Test
    void tracksSkippedRuns() {
        metrics.recordSkippedRun("cooldown");
        assertThat(metrics.skippedRunCount("cooldown")).isEqualTo(1);
    }

    @Test
    void computesAverageRunLatency() {
        metrics.recordRunLatency(10);
        metrics.recordRunLatency(20);

        assertThat(metrics.avgRunLatencyMs()).isEqualTo(15);
    }
}
