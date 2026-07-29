package ai.careerpilot.runtime;

import java.util.UUID;

/**
 * LangGraph Workflow Runtime — the final lifecycle step: turns an {@link ExecutionTrace} plus
 * either a successful {@link WorkflowExecutorOutcome} or a terminal failure into the immutable
 * {@link WorkflowExecutionResult} the caller receives. The only place {@link ExecutionEvent}s are
 * flattened into the result's plain-string {@code executionLogs}/{@code warnings}/{@code errors}.
 */
public interface WorkflowResultMapper {

    WorkflowExecutionResult mapOutcome(WorkflowExecutionContext context, WorkflowExecutorOutcome outcome,
                                        ExecutionTrace trace);

    /**
     * @param missionId nullable — a request-level failure (validation, unknown workflow) may or
     *                   may not have a mission id available yet, depending on how far execution got
     */
    WorkflowExecutionResult mapFailure(String workflowId, UUID missionId, String executionId, ExecutionTrace trace,
                                        WorkflowExecutionStatus status, String errorMessage);
}
