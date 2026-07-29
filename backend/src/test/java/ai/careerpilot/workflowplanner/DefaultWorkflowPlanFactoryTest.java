package ai.careerpilot.workflowplanner;

import ai.careerpilot.capability.CapabilityType;
import ai.careerpilot.domain.WorkflowDefinition;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultWorkflowPlanFactoryTest {

    private final DefaultWorkflowPlanFactory factory = new DefaultWorkflowPlanFactory();

    private WorkflowStep step(int number, List<String> inputs, List<String> outputs, boolean approval, boolean parallel) {
        return new WorkflowStep(number, "s" + number, "d", CapabilityType.RESUME_ANALYSIS, inputs, outputs, 1,
                Duration.ofMinutes(5), approval, List.of(), parallel, Duration.ofMinutes(2), "n" + number);
    }

    private WorkflowDefinition definition() {
        return WorkflowDefinition.builder().workflowId("RESUME_OPTIMIZATION_V1").version("v1")
                .requiredCapabilitiesJson("[\"RESUME_ANALYSIS\"]").requiredToolsJson("[\"filesystem\"]").build();
    }

    @Test
    void flattensAndDedupesRequiredInputsAndExpectedOutputs() {
        WorkflowStep s1 = step(1, List.of("resume"), List.of("score"), false, false);
        WorkflowStep s2 = step(2, List.of("resume", "job"), List.of("score", "keywords"), false, false);
        WorkflowStepGrouping grouping = new WorkflowStepGrouping(List.of(s1, s2), List.of());
        WorkflowEstimate estimate = new WorkflowEstimate(Duration.ofMinutes(7), 3000, java.math.BigDecimal.ONE, 2, 2, WorkflowComplexity.LOW, 0.65);
        WorkflowPlanRequest request = new WorkflowPlanRequest(UUID.randomUUID(), WorkflowType.RESUME);

        WorkflowPlan plan = factory.build(request, definition(), grouping, estimate);

        assertThat(plan.requiredInputs()).containsExactly("resume", "job");
        assertThat(plan.expectedOutputs()).containsExactly("score", "keywords");
    }

    @Test
    void approvalRequiredWhenAnyStepRequiresIt() {
        WorkflowStep s1 = step(1, List.of(), List.of(), false, false);
        WorkflowStep s2 = step(2, List.of(), List.of(), true, false);
        WorkflowStepGrouping grouping = new WorkflowStepGrouping(List.of(s1, s2), List.of());
        WorkflowEstimate estimate = new WorkflowEstimate(Duration.ZERO, 0, java.math.BigDecimal.ZERO, 0, 0, WorkflowComplexity.LOW, 0.65);
        WorkflowPlanRequest request = new WorkflowPlanRequest(UUID.randomUUID(), WorkflowType.RESUME);

        WorkflowPlan plan = factory.build(request, definition(), grouping, estimate);

        assertThat(plan.approvalRequired()).isTrue();
        assertThat(plan.executionStrategy()).isEqualTo(WorkflowExecutionStrategy.HUMAN_APPROVAL);
    }

    @Test
    void parallelStepsSelectParallelExecutionStrategy() {
        WorkflowStep s1 = step(1, List.of(), List.of(), false, false);
        WorkflowStep s2 = step(2, List.of(), List.of(), false, true);
        WorkflowStepGrouping grouping = new WorkflowStepGrouping(List.of(s1), List.of(s2));
        WorkflowEstimate estimate = new WorkflowEstimate(Duration.ZERO, 0, java.math.BigDecimal.ZERO, 0, 0, WorkflowComplexity.LOW, 0.65);
        WorkflowPlanRequest request = new WorkflowPlanRequest(UUID.randomUUID(), WorkflowType.RESUME);

        WorkflowPlan plan = factory.build(request, definition(), grouping, estimate);

        assertThat(plan.executionStrategy()).isEqualTo(WorkflowExecutionStrategy.PARALLEL);
    }

    @Test
    void derivesCapabilityTypeAndMcpCapabilitiesFromTheRegistryDefinition() {
        WorkflowStepGrouping grouping = new WorkflowStepGrouping(List.of(), List.of());
        WorkflowEstimate estimate = new WorkflowEstimate(Duration.ZERO, 0, java.math.BigDecimal.ZERO, 0, 0, WorkflowComplexity.LOW, 0.65);
        WorkflowPlanRequest request = new WorkflowPlanRequest(UUID.randomUUID(), WorkflowType.RESUME);

        WorkflowPlan plan = factory.build(request, definition(), grouping, estimate);

        assertThat(plan.capabilityType()).isEqualTo(CapabilityType.RESUME_ANALYSIS);
        assertThat(plan.requiredMcpCapabilities()).containsExactly("filesystem");
    }

    @Test
    void populatesFutureLangGraphHintsWithoutAnyLangGraphDependency() {
        WorkflowStepGrouping grouping = new WorkflowStepGrouping(List.of(), List.of());
        WorkflowEstimate estimate = new WorkflowEstimate(Duration.ZERO, 0, java.math.BigDecimal.ZERO, 0, 0, WorkflowComplexity.LOW, 0.65);
        WorkflowPlanRequest request = new WorkflowPlanRequest(UUID.randomUUID(), WorkflowType.RESUME);

        WorkflowPlan plan = factory.build(request, definition(), grouping, estimate);

        assertThat(plan.futureLangGraphGraphId()).isEqualTo("RESUME_GRAPH_V1");
        assertThat(plan.futureLangGraphEntryNode()).isEqualTo("start");
        assertThat(plan.futureLangGraphExitNode()).isEqualTo("end");
    }
}
