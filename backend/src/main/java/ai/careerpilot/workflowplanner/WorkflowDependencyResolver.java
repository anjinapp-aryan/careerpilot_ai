package ai.careerpilot.workflowplanner;

import java.util.List;

/**
 * Phase 8 — partitions a flat, ordered step list into sequential vs. parallel-safe groups, based
 * on each {@link WorkflowStep#canExecuteInParallel()} flag. Pure grouping only — dependency
 * existence/self-reference checks are {@link WorkflowValidator}'s job, not this one's.
 */
public interface WorkflowDependencyResolver {

    WorkflowStepGrouping resolve(List<WorkflowStep> steps);
}
