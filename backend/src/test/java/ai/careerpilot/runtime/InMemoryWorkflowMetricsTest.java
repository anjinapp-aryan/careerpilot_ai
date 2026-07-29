package ai.careerpilot.runtime;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryWorkflowMetricsTest {

    private final InMemoryWorkflowMetrics metrics = new InMemoryWorkflowMetrics();

    private WorkflowExecutionResult result(String workflowId, WorkflowExecutionStatus status, long millis) {
        Instant start = Instant.now();
        return new WorkflowExecutionResult(workflowId, "exec-" + workflowId, status, start,
                start.plusMillis(millis), Duration.ofMillis(millis), Map.of(), java.util.List.of(),
                java.util.List.of(), java.util.List.of(), Map.of());
    }

    @Test
    void tracksCountsByStatusAndWorkflow() {
        metrics.record(result("RESUME_OPTIMIZATION_V1", WorkflowExecutionStatus.COMPLETED, 100));
        metrics.record(result("RESUME_OPTIMIZATION_V1", WorkflowExecutionStatus.FAILED, 50));
        metrics.record(result("ATS_V1", WorkflowExecutionStatus.COMPLETED, 200));

        Map<String, Object> snapshot = metrics.snapshot();

        assertThat(snapshot.get("totalExecutions")).isEqualTo(3L);
        assertThat(snapshot.get("status.COMPLETED")).isEqualTo(2L);
        assertThat(snapshot.get("status.FAILED")).isEqualTo(1L);
        assertThat(snapshot.get("workflow.RESUME_OPTIMIZATION_V1")).isEqualTo(2L);
        assertThat(snapshot.get("averageDurationMillis")).isEqualTo((100L + 50L + 200L) / 3L);
        assertThat(snapshot).containsKey("lastExecutionAt");
    }

    @Test
    void emptySnapshotHasZeroTotals() {
        Map<String, Object> snapshot = metrics.snapshot();

        assertThat(snapshot.get("totalExecutions")).isEqualTo(0L);
        assertThat(snapshot.get("averageDurationMillis")).isEqualTo(0L);
        assertThat(snapshot).doesNotContainKey("lastExecutionAt");
    }
}
