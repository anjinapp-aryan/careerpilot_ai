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

class DefaultWorkflowStateFactoryTest {

    private final DefaultWorkflowStateFactory factory = new DefaultWorkflowStateFactory();

    @Test
    void buildsStateFromContextAndPassesInputsThrough() {
        UUID missionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        ExecutionDecision decision = new ExecutionDecision(WorkflowType.RESUME, ExecutionPolicy.AUTO,
                ExecutionPriority.NORMAL, 1, List.of(), List.of(), null, "r");
        ResolvedWorkflowDefinition definition = new ResolvedWorkflowDefinition("RESUME_OPTIMIZATION_V1",
                "Resume Optimization", "v1", "RESUME_OPTIMIZATION", "ACTIVE");
        WorkflowExecutionContext context = new WorkflowExecutionContext("exec-1", missionId, userId, decision,
                definition, null, "corr-1", Instant.now());
        Map<String, Object> inputs = Map.of("resumeVersion", "v3");

        WorkflowState state = factory.create(context, inputs);

        assertThat(state.missionId()).isEqualTo(missionId);
        assertThat(state.userId()).isEqualTo(userId);
        assertThat(state.workflowId()).isEqualTo("RESUME_OPTIMIZATION_V1");
        assertThat(state.executionId()).isEqualTo("exec-1");
        assertThat(state.inputs()).isEqualTo(inputs);
        assertThat(state.context()).containsEntry("correlationId", "corr-1");
    }
}
