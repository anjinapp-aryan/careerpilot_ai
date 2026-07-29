package ai.careerpilot.workflowplanner;

import ai.careerpilot.domain.WorkflowDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Orchestration only — each collaborator's own real behavior is covered by its dedicated test; this pins the call sequence and failure modes. */
class DefaultWorkflowPlannerTest {

    private final WorkflowSelector selector = mock(WorkflowSelector.class);
    private final WorkflowStepTemplateProvider steps = mock(WorkflowStepTemplateProvider.class);
    private final WorkflowDependencyResolver resolver = mock(WorkflowDependencyResolver.class);
    private final WorkflowEstimator estimator = mock(WorkflowEstimator.class);
    private final WorkflowPlanFactory factory = mock(WorkflowPlanFactory.class);
    private final WorkflowValidator validator = mock(WorkflowValidator.class);
    private final DefaultWorkflowPlanner planner =
            new DefaultWorkflowPlanner(selector, steps, resolver, estimator, factory, validator);

    private WorkflowPlanRequest request() {
        return new WorkflowPlanRequest(UUID.randomUUID(), WorkflowType.RESUME);
    }

    @Test
    void throwsWhenNoDefinitionIsRegisteredForTheType() {
        when(selector.select(WorkflowType.RESUME)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> planner.plan(request()))
                .isInstanceOf(WorkflowPlanningException.class)
                .hasMessageContaining("RESUME");
    }

    @Test
    void throwsWhenTheAssembledPlanFailsValidation() {
        WorkflowDefinition def = WorkflowDefinition.builder().workflowId("RESUME_OPTIMIZATION_V1").build();
        when(selector.select(WorkflowType.RESUME)).thenReturn(Optional.of(def));
        when(steps.stepsFor(WorkflowType.RESUME)).thenReturn(List.of());
        WorkflowStepGrouping grouping = new WorkflowStepGrouping(List.of(), List.of());
        when(resolver.resolve(any())).thenReturn(grouping);
        WorkflowEstimate estimate = mock(WorkflowEstimate.class);
        when(estimator.estimate(any(), any(), any())).thenReturn(estimate);
        WorkflowPlan plan = mock(WorkflowPlan.class);
        when(factory.build(any(), any(), any(), any())).thenReturn(plan);
        when(validator.validate(plan)).thenReturn(WorkflowValidationResult.invalid(List.of("no steps")));

        assertThatThrownBy(() -> planner.plan(request()))
                .isInstanceOf(WorkflowPlanningException.class)
                .hasMessageContaining("no steps");
    }

    @Test
    void returnsTheAssembledPlanWhenValid() {
        WorkflowDefinition def = WorkflowDefinition.builder().workflowId("RESUME_OPTIMIZATION_V1").build();
        when(selector.select(WorkflowType.RESUME)).thenReturn(Optional.of(def));
        List<WorkflowStep> templateSteps = List.of();
        when(steps.stepsFor(WorkflowType.RESUME)).thenReturn(templateSteps);
        WorkflowStepGrouping grouping = new WorkflowStepGrouping(List.of(), List.of());
        when(resolver.resolve(templateSteps)).thenReturn(grouping);
        WorkflowEstimate estimate = mock(WorkflowEstimate.class);
        when(estimator.estimate(WorkflowType.RESUME, templateSteps, WorkflowPriority.MEDIUM)).thenReturn(estimate);
        WorkflowPlan plan = mock(WorkflowPlan.class);
        WorkflowPlanRequest request = request();
        when(factory.build(request, def, grouping, estimate)).thenReturn(plan);
        when(validator.validate(plan)).thenReturn(WorkflowValidationResult.ok());

        assertThat(planner.plan(request)).isSameAs(plan);
    }
}
