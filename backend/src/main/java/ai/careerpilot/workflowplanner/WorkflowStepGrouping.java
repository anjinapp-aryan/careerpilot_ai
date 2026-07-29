package ai.careerpilot.workflowplanner;

import java.util.List;

/** Phase 8 — {@link WorkflowDependencyResolver}'s output: the same steps partitioned into sequential vs. parallel-safe groups. */
public record WorkflowStepGrouping(List<WorkflowStep> sequentialSteps, List<WorkflowStep> parallelSteps) {
}
