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

class DefaultWorkflowLifecycleManagerTest {

    private final DefaultWorkflowLifecycleManager manager = new DefaultWorkflowLifecycleManager();

    private WorkflowExecutionContext context() {
        ExecutionDecision decision = new ExecutionDecision(WorkflowType.RESUME, ExecutionPolicy.AUTO,
                ExecutionPriority.NORMAL, 1, List.of(), List.of(), null, "r");
        ResolvedWorkflowDefinition definition = new ResolvedWorkflowDefinition("RESUME_OPTIMIZATION_V1",
                "Resume Optimization", "v1", "RESUME_OPTIMIZATION", "ACTIVE");
        return new WorkflowExecutionContext("exec-1", UUID.randomUUID(), UUID.randomUUID(), decision, definition,
                null, "corr-1", Instant.now());
    }

    @Test
    void beginStartsTheTraceAndRecordsAStartedEvent() {
        ExecutionTrace trace = manager.begin(context());

        assertThat(trace.startTime()).isNotNull();
        assertThat(trace.events()).hasSize(1);
        assertThat(trace.events().get(0).phase()).isEqualTo("STARTED");
    }

    @Test
    void completeEndsTheTraceAndRecordsAnInfoEvent() {
        ExecutionTrace trace = manager.begin(context());

        manager.complete(trace, new WorkflowExecutorOutcome(WorkflowExecutionStatus.COMPLETED, Map.of(), "ref", Map.of()));

        assertThat(trace.endTime()).isNotNull();
        assertThat(trace.events()).anyMatch(e -> e.phase().equals("COMPLETED") && e.level().equals("INFO"));
    }

    @Test
    void failEndsTheTraceAndRecordsAnErrorEvent() {
        ExecutionTrace trace = manager.begin(context());

        manager.fail(trace, "EXECUTION_FAILED", new RuntimeException("boom"));

        assertThat(trace.endTime()).isNotNull();
        assertThat(trace.events()).anyMatch(e -> e.phase().equals("EXECUTION_FAILED") && e.level().equals("ERROR")
                && e.message().equals("boom"));
    }
}
