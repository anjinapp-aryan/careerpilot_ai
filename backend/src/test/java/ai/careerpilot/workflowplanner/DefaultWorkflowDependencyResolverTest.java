package ai.careerpilot.workflowplanner;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultWorkflowDependencyResolverTest {

    private final DefaultWorkflowDependencyResolver resolver = new DefaultWorkflowDependencyResolver();

    private WorkflowStep step(int number, boolean parallel) {
        return new WorkflowStep(number, "s" + number, "d", null, List.of(), List.of(), 1,
                Duration.ofMinutes(1), false, List.of(), parallel, Duration.ofMinutes(1), "n" + number);
    }

    @Test
    void partitionsStepsByCanExecuteInParallel() {
        List<WorkflowStep> steps = List.of(step(1, false), step(2, true), step(3, false), step(4, true));

        WorkflowStepGrouping grouping = resolver.resolve(steps);

        assertThat(grouping.sequentialSteps()).extracting(WorkflowStep::stepNumber).containsExactly(1, 3);
        assertThat(grouping.parallelSteps()).extracting(WorkflowStep::stepNumber).containsExactly(2, 4);
    }

    @Test
    void emptyInputProducesEmptyGroups() {
        WorkflowStepGrouping grouping = resolver.resolve(List.of());

        assertThat(grouping.sequentialSteps()).isEmpty();
        assertThat(grouping.parallelSteps()).isEmpty();
    }
}
