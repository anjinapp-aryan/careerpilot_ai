package ai.careerpilot.intent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class InMemoryIntentMetricsTest {

    private final InMemoryIntentMetrics metrics = new InMemoryIntentMetrics();

    @Test
    void tracksSelectionCounts() {
        metrics.recordIntentSelected("GITHUB_ANALYSIS");
        metrics.recordIntentSelected("GITHUB_ANALYSIS");
        metrics.recordIntentSelected("RESUME_ANALYSIS");

        assertThat(metrics.selectionCount("GITHUB_ANALYSIS")).isEqualTo(2);
        assertThat(metrics.selectionCount("RESUME_ANALYSIS")).isEqualTo(1);
        assertThat(metrics.selectionCount("never_selected")).isZero();
    }

    @Test
    void computesAverageLatency() {
        metrics.recordIntentLatency(10);
        metrics.recordIntentLatency(20);

        assertThat(metrics.avgLatencyMs()).isEqualTo(15);
    }

    @Test
    void computesAverageConfidence() {
        metrics.recordConfidence(0.5);
        metrics.recordConfidence(1.0);

        assertThat(metrics.avgConfidence()).isCloseTo(0.75, within(0.001));
    }

    @Test
    void tracksFallbackReasons() {
        metrics.recordFallback("no candidates resolved");
        metrics.recordFallback("no candidates resolved");

        assertThat(metrics.fallbackCount("no candidates resolved")).isEqualTo(2);
        assertThat(metrics.fallbackCount("never happened")).isZero();
    }
}
