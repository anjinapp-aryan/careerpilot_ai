package ai.careerpilot.missionexecution;

import ai.careerpilot.workflowplanner.WorkflowType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Pre-Phase-9 Hardening — the sole output of {@link MissionExecutionEngine#plan}: which
 * workflows execute now, which wait, which run in parallel, which are blocked, which need
 * approval, which retry, and the overall execution order/timeline. Data only — this package
 * never executes anything described here. A future LangGraph Workflow Engine (Phase 9+) is the
 * intended consumer.
 */
public record MissionExecutionPlan(UUID missionId, List<ExecutionDecision> decisions, ExecutionQueue executionQueue,
                                    List<WorkflowType> executionOrder, List<ExecutionGroup> parallelGroups,
                                    List<ExecutionDecision> approvalQueue, List<ExecutionDecision> blockedWorkflows,
                                    List<ExecutionDecision> retryQueue, List<ExecutionDecision> futureQueue,
                                    MissionExecutionEstimate estimate, Instant createdAt) {
}
