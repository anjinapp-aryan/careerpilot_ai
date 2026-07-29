package ai.careerpilot.workflowplanner;

import ai.careerpilot.domain.WorkflowDefinition;

import java.util.List;

/**
 * Phase 8 — the only {@link WorkflowPlanner}: select the registry definition → fetch the step
 * template → resolve dependencies → estimate → assemble → validate. Never executes anything;
 * never calls Spring AI, MCP, LangGraph, or the AI Gateway.
 */
public class DefaultWorkflowPlanner implements WorkflowPlanner {

    private final WorkflowSelector selector;
    private final WorkflowStepTemplateProvider steps;
    private final WorkflowDependencyResolver dependencyResolver;
    private final WorkflowEstimator estimator;
    private final WorkflowPlanFactory planFactory;
    private final WorkflowValidator validator;

    public DefaultWorkflowPlanner(WorkflowSelector selector, WorkflowStepTemplateProvider steps,
                                   WorkflowDependencyResolver dependencyResolver, WorkflowEstimator estimator,
                                   WorkflowPlanFactory planFactory, WorkflowValidator validator) {
        this.selector = selector;
        this.steps = steps;
        this.dependencyResolver = dependencyResolver;
        this.estimator = estimator;
        this.planFactory = planFactory;
        this.validator = validator;
    }

    @Override
    public WorkflowPlan plan(WorkflowPlanRequest request) {
        WorkflowDefinition definition = selector.select(request.workflowType())
                .orElseThrow(() -> new WorkflowPlanningException(
                        "No registered workflow definition for type: " + request.workflowType()));

        List<WorkflowStep> templateSteps = steps.stepsFor(request.workflowType());
        WorkflowStepGrouping grouping = dependencyResolver.resolve(templateSteps);
        WorkflowEstimate estimate = estimator.estimate(request.workflowType(), templateSteps, request.priority());
        WorkflowPlan plan = planFactory.build(request, definition, grouping, estimate);

        WorkflowValidationResult validation = validator.validate(plan);
        if (!validation.valid()) {
            throw new WorkflowPlanningException(
                    "Invalid workflow plan for " + request.workflowType() + ": " + validation.errors());
        }
        return plan;
    }
}
