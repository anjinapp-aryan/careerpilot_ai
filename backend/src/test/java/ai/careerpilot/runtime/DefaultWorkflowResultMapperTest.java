package ai.careerpilot.runtime;

import ai.careerpilot.missionexecution.ExecutionDecision;
import ai.careerpilot.missionexecution.ExecutionPolicy;
import ai.careerpilot.missionexecution.ExecutionPriority;
import ai.careerpilot.workflowplanner.WorkflowType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultWorkflowResultMapperTest {

    private final DefaultWorkflowResultMapper mapper = new DefaultWorkflowResultMapper();

    private final UUID missionId = UUID.randomUUID();

    private WorkflowExecutionContext context() {
        ExecutionDecision decision = new ExecutionDecision(WorkflowType.RESUME, ExecutionPolicy.AUTO,
                ExecutionPriority.NORMAL, 1, List.of(), List.of(), null, "r");
        ResolvedWorkflowDefinition definition = new ResolvedWorkflowDefinition("RESUME_OPTIMIZATION_V1",
                "Resume Optimization", "v1", "RESUME_OPTIMIZATION", "ACTIVE");
        return new WorkflowExecutionContext("exec-1", missionId, UUID.randomUUID(), decision, definition,
                null, "corr-1", Instant.now());
    }

    @Test
    void mapsASuccessfulOutcomeIntoAResult() {
        ExecutionTrace trace = new ExecutionTrace();
        trace.start();
        trace.record(ExecutionEvent.info("STARTED", "go"));
        trace.record(ExecutionEvent.warn("INVOKING", "slow provider"));
        trace.end();
        WorkflowExecutorOutcome outcome = new WorkflowExecutorOutcome(WorkflowExecutionStatus.COMPLETED,
                Map.of("ats_score", 92), "thread-1", Map.of("thread_id", "thread-1"));

        WorkflowExecutionResult result = mapper.mapOutcome(context(), outcome, trace);

        assertThat(result.workflowId()).isEqualTo("RESUME_OPTIMIZATION_V1");
        assertThat(result.executionId()).isEqualTo("exec-1");
        assertThat(result.executionStatus()).isEqualTo(WorkflowExecutionStatus.COMPLETED);
        assertThat(result.outputPayload()).containsEntry("ats_score", 92);
        assertThat(result.executionLogs()).hasSize(2);
        assertThat(result.warnings()).containsExactly("slow provider");
        assertThat(result.errors()).isEmpty();
        assertThat(result.metrics()).containsEntry("workflowVersion", "v1");
        assertThat(result.metrics()).containsEntry("missionId", missionId.toString());
        assertThat(result.duration()).isNotNull();
        assertThat(result.successful()).isTrue();
    }

    @Test
    void mapsAFailureIntoATerminalResult() {
        ExecutionTrace trace = new ExecutionTrace();
        trace.start();
        trace.record(ExecutionEvent.error("EXECUTION_FAILED", "agent unavailable"));
        trace.end();

        WorkflowExecutionResult result = mapper.mapFailure("RESUME_OPTIMIZATION_V1", missionId, "exec-1", trace,
                WorkflowExecutionStatus.FAILED, "agent unavailable");

        assertThat(result.executionStatus()).isEqualTo(WorkflowExecutionStatus.FAILED);
        assertThat(result.errors()).containsExactly("agent unavailable");
        assertThat(result.successful()).isFalse();
        assertThat(result.metrics()).containsEntry("missionId", missionId.toString());
    }

    @Test
    void mapsAFailureWithNullMissionIdWithoutThrowing() {
        ExecutionTrace trace = new ExecutionTrace();
        trace.start();
        trace.end();

        WorkflowExecutionResult result = mapper.mapFailure("UNKNOWN", null, "exec-1", trace,
                WorkflowExecutionStatus.FAILED, "validation failed");

        assertThat(result.metrics()).doesNotContainKey("missionId");
    }
}
