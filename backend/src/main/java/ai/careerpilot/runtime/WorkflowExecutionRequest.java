package ai.careerpilot.runtime;

import ai.careerpilot.missionexecution.ExecutionDecision;

import java.util.Map;
import java.util.UUID;

/**
 * LangGraph Workflow Runtime — the single public entry point's input. {@link #workflowId()} (a
 * plain Workflow Registry {@code workflow_type} key, e.g. {@code "SKILL_GAP_INTELLIGENCE"}) is
 * the canonical identity used for registry resolution — the runtime never needs an {@link
 * ExecutionDecision} to know which workflow to run.
 *
 * <h2>Phase 10A — generalized beyond the Mission Execution Engine</h2>
 * {@link #executionDecision()} is now <b>optional</b> (nullable): present when the caller is the
 * Mission Execution Engine flow and wants its policy/priority/blocking context carried through
 * into {@link WorkflowExecutionContext} and {@link WorkflowExecutionResult#metrics()}; absent for
 * an ad-hoc or generically-dispatched run (e.g. a future workflow registration driving execution
 * directly). Before Phase 10A this field was mandatory and was the sole source of {@link
 * #workflowId()} (derived from {@code executionDecision.workflowType()}, which required a {@code
 * ai.careerpilot.workflowplanner.WorkflowType} enum value — every new workflow would have needed
 * an enum change). {@link #forDecision} preserves that ergonomic path without the hard coupling:
 * {@code workflowId} is still derived automatically from the decision, but is now the field the
 * runtime actually reads.
 */
public record WorkflowExecutionRequest(UUID missionId, UUID userId, String workflowId,
                                        ExecutionDecision executionDecision,
                                        Map<String, Object> inputs, String correlationId) {

    public WorkflowExecutionRequest {
        inputs = inputs == null ? Map.of() : Map.copyOf(inputs);
    }

    /**
     * Builds a request from an {@link ExecutionDecision} produced by the Mission Execution
     * Engine — the pre-Phase-10A call shape, preserved for ergonomics. {@code workflowId} is
     * derived from {@code decision.workflowType().registryWorkflowType()} automatically.
     */
    public static WorkflowExecutionRequest forDecision(UUID missionId, UUID userId, ExecutionDecision decision,
                                                        Map<String, Object> inputs, String correlationId) {
        return new WorkflowExecutionRequest(missionId, userId, decision.workflowType().registryWorkflowType(),
                decision, inputs, correlationId);
    }
}
