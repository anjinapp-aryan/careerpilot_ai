package ai.careerpilot.workflowplanner;

import ai.careerpilot.domain.WorkflowDefinition;

/** Phase 8 — assembles a {@link WorkflowPlan} from a request, its resolved registry definition, its step grouping, and its estimate. */
public interface WorkflowPlanFactory {

    WorkflowPlan build(WorkflowPlanRequest request, WorkflowDefinition definition,
                        WorkflowStepGrouping grouping, WorkflowEstimate estimate);
}
