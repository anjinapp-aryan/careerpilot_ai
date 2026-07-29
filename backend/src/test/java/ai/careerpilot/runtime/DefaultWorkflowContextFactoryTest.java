package ai.careerpilot.runtime;

import ai.careerpilot.missionexecution.ExecutionDecision;
import ai.careerpilot.missionexecution.ExecutionPolicy;
import ai.careerpilot.missionexecution.ExecutionPriority;
import ai.careerpilot.workflowplanner.WorkflowType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultWorkflowContextFactoryTest {

    private final DefaultWorkflowContextFactory factory = new DefaultWorkflowContextFactory();

    private ExecutionDecision decision() {
        return new ExecutionDecision(WorkflowType.RESUME, ExecutionPolicy.AUTO, ExecutionPriority.NORMAL, 1,
                List.of(), List.of(), null, "r");
    }

    @Test
    void buildsContextFromRequestAndDefinition() {
        UUID missionId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        WorkflowExecutionRequest request = WorkflowExecutionRequest.forDecision(missionId, userId, decision(), Map.of(), "corr-1");
        ResolvedWorkflowDefinition definition = new ResolvedWorkflowDefinition("RESUME_OPTIMIZATION_V1",
                "Resume Optimization", "v1", "RESUME_OPTIMIZATION", "ACTIVE");

        WorkflowExecutionContext context = factory.create(request, definition, "exec-1");

        assertThat(context.executionId()).isEqualTo("exec-1");
        assertThat(context.missionId()).isEqualTo(missionId);
        assertThat(context.userId()).isEqualTo(userId);
        assertThat(context.definition()).isEqualTo(definition);
        assertThat(context.correlationId()).isEqualTo("corr-1");
        assertThat(context.requestedAt()).isNotNull();
    }

    @Test
    void generatesACorrelationIdWhenNoneSupplied() {
        WorkflowExecutionRequest request = WorkflowExecutionRequest.forDecision(UUID.randomUUID(), UUID.randomUUID(),
                decision(), Map.of(), null);
        ResolvedWorkflowDefinition definition = new ResolvedWorkflowDefinition("RESUME_OPTIMIZATION_V1",
                "Resume Optimization", "v1", "RESUME_OPTIMIZATION", "ACTIVE");

        WorkflowExecutionContext context = factory.create(request, definition, "exec-1");

        assertThat(context.correlationId()).isNotBlank();
    }
}
