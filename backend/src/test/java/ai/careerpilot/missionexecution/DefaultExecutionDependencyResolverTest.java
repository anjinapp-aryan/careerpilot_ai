package ai.careerpilot.missionexecution;

import ai.careerpilot.workflowplanner.WorkflowType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultExecutionDependencyResolverTest {

    private final DefaultExecutionDependencyResolver resolver = new DefaultExecutionDependencyResolver();

    private ExecutionContext context(Map<String, Double> metrics, Map<WorkflowType, ExecutionState> states) {
        return new ExecutionContext(UUID.randomUUID(), List.of(), 0, states, metrics, List.of());
    }

    @Test
    void typeWithNoRulesIsNeverBlocked() {
        DependencyEvaluation eval = resolver.evaluate(WorkflowType.PORTFOLIO, context(Map.of(), Map.of()));

        assertThat(eval.blocked()).isFalse();
    }

    @Test
    void atsIsBlockedWhenResumeScorePreconditionUnmet() {
        DependencyEvaluation eval = resolver.evaluate(WorkflowType.ATS, context(Map.of("resume.score", 70.0), Map.of()));

        assertThat(eval.blocked()).isTrue();
        assertThat(eval.unmetPreconditions()).hasSize(1);
    }

    @Test
    void atsIsEligibleWhenResumeScorePreconditionMet() {
        DependencyEvaluation eval = resolver.evaluate(WorkflowType.ATS, context(Map.of("resume.score", 95.0), Map.of()));

        assertThat(eval.blocked()).isFalse();
    }

    @Test
    void interviewIsBlockedByIncompletePrerequisiteWorkflows() {
        Map<WorkflowType, ExecutionState> states = Map.of(WorkflowType.RESUME, ExecutionState.COMPLETED);
        DependencyEvaluation eval = resolver.evaluate(WorkflowType.INTERVIEW,
                context(Map.of("resume.score", 95.0), states)); // ATS prerequisite still missing

        assertThat(eval.blocked()).isTrue();
        assertThat(eval.blockedByWorkflows()).containsExactly(WorkflowType.ATS);
    }

    @Test
    void interviewIsEligibleWhenAllPrerequisitesCompletedAndPreconditionsMet() {
        Map<WorkflowType, ExecutionState> states = Map.of(
                WorkflowType.RESUME, ExecutionState.COMPLETED, WorkflowType.ATS, ExecutionState.COMPLETED);
        DependencyEvaluation eval = resolver.evaluate(WorkflowType.INTERVIEW, context(Map.of("resume.score", 95.0), states));

        assertThat(eval.blocked()).isFalse();
    }

    @Test
    void relocationIsBlockedUntilVisaDocumentsAvailableAndVisaWorkflowCompleted() {
        DependencyEvaluation notReady = resolver.evaluate(WorkflowType.RELOCATION, context(Map.of(), Map.of()));
        assertThat(notReady.blocked()).isTrue();

        Map<WorkflowType, ExecutionState> visaDone = Map.of(WorkflowType.VISA, ExecutionState.COMPLETED);
        DependencyEvaluation ready = resolver.evaluate(WorkflowType.RELOCATION,
                context(Map.of("visa.documentsAvailable", 1.0), visaDone));
        assertThat(ready.blocked()).isFalse();
    }
}
