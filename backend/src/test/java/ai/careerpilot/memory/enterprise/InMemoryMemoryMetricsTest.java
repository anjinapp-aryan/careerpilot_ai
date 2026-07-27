package ai.careerpilot.memory.enterprise;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryMemoryMetricsTest {

    private final InMemoryMemoryMetrics metrics = new InMemoryMemoryMetrics();

    @Test
    void tracksRememberAndForgetCountsPerType() {
        metrics.recordRemember("WORKING");
        metrics.recordRemember("WORKING");
        metrics.recordForget("WORKING");

        assertThat(metrics.rememberCount("WORKING")).isEqualTo(2);
        assertThat(metrics.forgetCount("WORKING")).isEqualTo(1);
    }

    @Test
    void computesAverageRetrievalAndSearchLatency() {
        metrics.recordRetrieval("WORKING", 10);
        metrics.recordRetrieval("WORKING", 20);
        metrics.recordSearch(5, 3);
        metrics.recordSearch(15, 1);

        assertThat(metrics.avgRetrievalLatencyMs()).isEqualTo(15);
        assertThat(metrics.avgSearchLatencyMs()).isEqualTo(10);
    }

    @Test
    void tracksConsolidationTotals() {
        metrics.recordConsolidation(2, 1);
        metrics.recordConsolidation(3, 0);

        assertThat(metrics.totalPromoted()).isEqualTo(5);
        assertThat(metrics.totalEvicted()).isEqualTo(1);
    }
}
