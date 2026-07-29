package ai.careerpilot.workflowplanner;

import java.util.Map;
import java.util.UUID;

/**
 * Phase 8 — the input to {@link WorkflowPlanner#plan}. {@code missionId} is required (this is
 * always "how should this mission recommendation execute"); {@code strategyId} is nullable (not
 * every plan request is tied to a specific {@code StrategyPlan}). {@code context} is a free-form,
 * caller-supplied bag consulted only by {@link WorkflowEstimator}/{@link WorkflowPlanFactory} for
 * light heuristics — never a channel for business rules the Mission/Strategy Engines should own.
 */
public record WorkflowPlanRequest(UUID missionId, UUID strategyId, WorkflowType workflowType,
                                   WorkflowPriority priority, Map<String, Object> context) {

    public WorkflowPlanRequest(UUID missionId, WorkflowType workflowType) {
        this(missionId, null, workflowType, WorkflowPriority.MEDIUM, Map.of());
    }
}
