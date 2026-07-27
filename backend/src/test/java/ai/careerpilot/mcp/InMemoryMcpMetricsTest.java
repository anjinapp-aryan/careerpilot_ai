package ai.careerpilot.mcp;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link InMemoryMcpMetrics} — Phase 10.2's real observability implementation. */
class InMemoryMcpMetricsTest {

    private final InMemoryMcpMetrics metrics = new InMemoryMcpMetrics();

    @Test
    void tracksServerAndToolRegistrationCounts() {
        metrics.recordServerRegistered("filesystem");
        metrics.recordServerRegistered("postgres");
        metrics.recordToolRegistered("get_latest_resume_document");

        assertThat(metrics.registeredServerCount()).isEqualTo(2);
        assertThat(metrics.registeredToolCount()).isEqualTo(1);
    }

    @Test
    void tracksExecutionSuccessAndFailureSeparatelyPerTool() {
        metrics.recordToolExecution("get_job_recommendations", true, 10);
        metrics.recordToolExecution("get_job_recommendations", true, 20);
        metrics.recordToolExecution("get_job_recommendations", false, 30);

        assertThat(metrics.executionCount("get_job_recommendations")).isEqualTo(3);
        assertThat(metrics.successCount("get_job_recommendations")).isEqualTo(2);
        assertThat(metrics.failureCount("get_job_recommendations")).isEqualTo(1);
    }

    @Test
    void computesAverageLatencyPerTool() {
        metrics.recordToolExecution("analyze_github_profile", true, 100);
        metrics.recordToolExecution("analyze_github_profile", true, 200);

        assertThat(metrics.avgLatencyMs("analyze_github_profile")).isEqualTo(150);
    }

    @Test
    void unknownToolReportsZeroesRatherThanThrowing() {
        assertThat(metrics.executionCount("never_called")).isZero();
        assertThat(metrics.avgLatencyMs("never_called")).isZero();
    }
}
