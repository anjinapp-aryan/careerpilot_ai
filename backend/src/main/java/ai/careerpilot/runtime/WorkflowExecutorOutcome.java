package ai.careerpilot.runtime;

import java.util.Map;

/**
 * LangGraph Workflow Runtime — the raw result a {@link WorkflowExecutor} hands back to the
 * runtime, before {@link WorkflowResultMapper} turns it into a {@link WorkflowExecutionResult}.
 * {@link #executionRef()} is the executor's own correlation handle for this run (e.g. the
 * LangGraph {@code thread_id} for {@link LangGraphWorkflowExecutor}) — opaque to the runtime,
 * carried through into {@link WorkflowExecutionResult#metrics()} for observability only.
 */
public record WorkflowExecutorOutcome(WorkflowExecutionStatus status, Map<String, Object> outputPayload,
                                       String executionRef, Map<String, Object> rawMetadata) {

    public WorkflowExecutorOutcome {
        outputPayload = outputPayload == null ? Map.of() : Map.copyOf(outputPayload);
        rawMetadata = rawMetadata == null ? Map.of() : Map.copyOf(rawMetadata);
    }
}
