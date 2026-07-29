package ai.careerpilot.workflowplanner;

import java.util.List;

/** Phase 8 — the only {@link WorkflowDependencyResolver}: a plain partition by {@link WorkflowStep#canExecuteInParallel()}. */
public class DefaultWorkflowDependencyResolver implements WorkflowDependencyResolver {

    @Override
    public WorkflowStepGrouping resolve(List<WorkflowStep> steps) {
        List<WorkflowStep> sequential = steps.stream().filter(s -> !s.canExecuteInParallel()).toList();
        List<WorkflowStep> parallel = steps.stream().filter(WorkflowStep::canExecuteInParallel).toList();
        return new WorkflowStepGrouping(sequential, parallel);
    }
}
