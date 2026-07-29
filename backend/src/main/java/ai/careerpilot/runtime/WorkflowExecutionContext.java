package ai.careerpilot.runtime;

import ai.careerpilot.missionexecution.ExecutionDecision;

import java.time.Instant;
import java.util.UUID;

/**
 * LangGraph Workflow Runtime — everything a {@link WorkflowExecutor} needs to run one {@link
 * ExecutionDecision}: who/what it's for ({@link #missionId()}/{@link #userId()}), which registry
 * entry it resolved to ({@link #definition()}), the mutable-by-copy {@link #state()}, and a {@link
 * #correlationId()} for cross-system tracing. Built once per execution by {@link
 * WorkflowContextFactory} and never mutated in place — {@link #withState(WorkflowState)} returns a
 * new instance.
 */
public record WorkflowExecutionContext(String executionId, UUID missionId, UUID userId,
                                        ExecutionDecision executionDecision, ResolvedWorkflowDefinition definition,
                                        WorkflowState state, String correlationId, Instant requestedAt) {

    public WorkflowExecutionContext withState(WorkflowState newState) {
        return new WorkflowExecutionContext(executionId, missionId, userId, executionDecision, definition,
                newState, correlationId, requestedAt);
    }
}
