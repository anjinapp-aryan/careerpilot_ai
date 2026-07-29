package ai.careerpilot.missionexecution;

import ai.careerpilot.workflowplanner.WorkflowType;

import java.util.List;

/** Pre-Phase-9 Hardening — {@link ExecutionDependencyResolver}'s per-workflow verdict. */
public record DependencyEvaluation(boolean blocked, List<WorkflowType> blockedByWorkflows,
                                    List<Precondition> unmetPreconditions) {

    public static DependencyEvaluation clear() {
        return new DependencyEvaluation(false, List.of(), List.of());
    }
}
