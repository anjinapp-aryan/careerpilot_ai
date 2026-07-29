package ai.careerpilot.runtime;

import java.util.Map;

/**
 * LangGraph Workflow Runtime — a thin, deliberately unopinionated {@link WorkflowState} builder.
 * It copies {@link WorkflowExecutionContext} identifiers across and passes {@code inputs} through
 * unchanged — it never inspects, validates, or enriches the payload, since this runtime doesn't
 * know what any given workflow type actually needs as input.
 */
public class DefaultWorkflowStateFactory implements WorkflowStateFactory {

    @Override
    public WorkflowState create(WorkflowExecutionContext context, Map<String, Object> inputs) {
        return new WorkflowState(context.missionId(), context.userId(), context.definition().workflowId(),
                context.executionId(), Map.of("correlationId", context.correlationId()), inputs, Map.of(), Map.of());
    }
}
