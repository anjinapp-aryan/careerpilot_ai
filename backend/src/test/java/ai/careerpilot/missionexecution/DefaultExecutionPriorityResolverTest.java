package ai.careerpilot.missionexecution;

import ai.careerpilot.workflowplanner.WorkflowComplexity;
import ai.careerpilot.workflowplanner.WorkflowPlan;
import ai.careerpilot.workflowplanner.WorkflowPriority;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultExecutionPriorityResolverTest {

    private final DefaultExecutionPriorityResolver resolver = new DefaultExecutionPriorityResolver();
    private final ExecutionContext context = new ExecutionContext(UUID.randomUUID(), List.of());

    private WorkflowPlan planWith(WorkflowPriority priority, WorkflowComplexity complexity) {
        WorkflowPlan plan = mock(WorkflowPlan.class);
        when(plan.priority()).thenReturn(priority);
        when(plan.estimatedComplexity()).thenReturn(complexity);
        return plan;
    }

    @Test
    void directMappingForCriticalHighMedium() {
        assertThat(resolver.resolve(planWith(WorkflowPriority.CRITICAL, WorkflowComplexity.HIGH), context))
                .isEqualTo(ExecutionPriority.CRITICAL);
        assertThat(resolver.resolve(planWith(WorkflowPriority.HIGH, WorkflowComplexity.HIGH), context))
                .isEqualTo(ExecutionPriority.HIGH);
        assertThat(resolver.resolve(planWith(WorkflowPriority.MEDIUM, WorkflowComplexity.HIGH), context))
                .isEqualTo(ExecutionPriority.NORMAL);
    }

    @Test
    void lowPriorityWithLowComplexityDowngradesToOptional() {
        assertThat(resolver.resolve(planWith(WorkflowPriority.LOW, WorkflowComplexity.LOW), context))
                .isEqualTo(ExecutionPriority.OPTIONAL);
    }

    @Test
    void lowPriorityWithHigherComplexityStaysLow() {
        assertThat(resolver.resolve(planWith(WorkflowPriority.LOW, WorkflowComplexity.MEDIUM), context))
                .isEqualTo(ExecutionPriority.LOW);
        assertThat(resolver.resolve(planWith(WorkflowPriority.LOW, WorkflowComplexity.HIGH), context))
                .isEqualTo(ExecutionPriority.LOW);
    }
}
