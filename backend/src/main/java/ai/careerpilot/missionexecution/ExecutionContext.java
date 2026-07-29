package ai.careerpilot.missionexecution;

import ai.careerpilot.workflowplanner.WorkflowPlan;
import ai.careerpilot.workflowplanner.WorkflowType;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pre-Phase-9 Hardening — everything {@link MissionExecutionEngine#plan} needs: the {@link
 * WorkflowPlan}s already produced by the Workflow Planner (Phase 8, unchanged), the mission's
 * current progress, each in-flight workflow's {@link ExecutionState}, a caller-supplied metrics
 * bag for {@link Precondition} evaluation, and previously-recorded {@link ExecutionResultSummary}s.
 * This package never fetches any of this itself — no repository, no service call beyond what's
 * passed in here — matching the "never own business rules" discipline established for the
 * Mission-Aware Autonomous Career Agent (Phase 7A).
 */
public record ExecutionContext(UUID missionId, List<WorkflowPlan> workflowPlans, int missionProgressPercent,
                                Map<WorkflowType, ExecutionState> currentStates, Map<String, Double> metrics,
                                List<ExecutionResultSummary> previousResults) {

    public ExecutionContext(UUID missionId, List<WorkflowPlan> workflowPlans) {
        this(missionId, workflowPlans, 0, Map.of(), Map.of(), List.of());
    }
}
