package ai.careerpilot.workflowplanner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultWorkflowStepTemplateProviderTest {

    private final DefaultWorkflowStepTemplateProvider provider = new DefaultWorkflowStepTemplateProvider();

    @ParameterizedTest
    @EnumSource(WorkflowType.class)
    void everyWorkflowTypeHasAStepTemplateEndingInApproval(WorkflowType type) {
        List<WorkflowStep> steps = provider.stepsFor(type);

        assertThat(steps).isNotEmpty();
        assertThat(steps.get(steps.size() - 1).approvalRequired()).isTrue();
        assertThat(steps.stream().map(WorkflowStep::stepNumber).distinct().count()).isEqualTo(steps.size());
    }

    @Test
    void resumeWorkflowMatchesThePhaseSpecsWorkedExample() {
        List<WorkflowStep> steps = provider.stepsFor(WorkflowType.RESUME);

        assertThat(steps).extracting(WorkflowStep::stepName)
                .containsExactly("Analyze Resume", "ATS Optimization", "Generate Improvements", "Approval");
    }
}
