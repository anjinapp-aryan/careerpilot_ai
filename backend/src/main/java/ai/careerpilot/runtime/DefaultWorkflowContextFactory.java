package ai.careerpilot.runtime;

import java.time.Instant;
import java.util.UUID;

/**
 * LangGraph Workflow Runtime — assembles a {@link WorkflowExecutionContext} from an
 * already-validated request and an already-resolved definition. {@link #create} does not build
 * {@link WorkflowState} itself — that's {@link WorkflowStateFactory}'s job, applied by {@link
 * DefaultWorkflowRuntime} once the context exists — keeping this factory a single, small step.
 */
public class DefaultWorkflowContextFactory implements WorkflowContextFactory {

    @Override
    public WorkflowExecutionContext create(WorkflowExecutionRequest request, ResolvedWorkflowDefinition definition,
                                            String executionId) {
        String correlationId = request.correlationId() != null && !request.correlationId().isBlank()
                ? request.correlationId() : UUID.randomUUID().toString();
        return new WorkflowExecutionContext(executionId, request.missionId(), request.userId(),
                request.executionDecision(), definition, null, correlationId, Instant.now());
    }
}
