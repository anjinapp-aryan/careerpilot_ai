package ai.careerpilot.missionexecution;

import ai.careerpilot.workflowplanner.WorkflowType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultExecutionValidatorTest {

    private final DefaultExecutionValidator validator = new DefaultExecutionValidator();

    private ExecutionDecision decision(WorkflowType type, ExecutionPolicy policy) {
        return new ExecutionDecision(type, policy, ExecutionPriority.NORMAL, 1,
                policy == ExecutionPolicy.BLOCKED ? List.of(WorkflowType.RESUME) : List.of(), List.of(), null, "r");
    }

    private MissionExecutionPlan plan(List<ExecutionDecision> decisions, List<ExecutionDecision> approvalQueue,
                                       List<ExecutionDecision> blocked, List<ExecutionDecision> retry) {
        return new MissionExecutionPlan(UUID.randomUUID(), decisions,
                new ExecutionQueue(List.of(), List.of(), List.of()), List.of(), List.of(),
                approvalQueue, blocked, retry, List.of(), null, Instant.now());
    }

    @Test
    void validPlanPasses() {
        ExecutionDecision d = decision(WorkflowType.RESUME, ExecutionPolicy.AUTO);

        ExecutionValidationResult result = validator.validate(plan(List.of(d), List.of(), List.of(), List.of()));

        assertThat(result.valid()).isTrue();
    }

    @Test
    void duplicateWorkflowTypeAcrossDecisionsIsInvalid() {
        ExecutionDecision d1 = decision(WorkflowType.RESUME, ExecutionPolicy.AUTO);
        ExecutionDecision d2 = decision(WorkflowType.RESUME, ExecutionPolicy.RETRY);

        ExecutionValidationResult result = validator.validate(plan(List.of(d1, d2), List.of(), List.of(), List.of()));

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("Duplicate"));
    }

    @Test
    void approvalQueueContainingWrongPolicyIsInvalid() {
        ExecutionDecision autoDecision = decision(WorkflowType.RESUME, ExecutionPolicy.AUTO);

        ExecutionValidationResult result = validator.validate(
                plan(List.of(autoDecision), List.of(autoDecision), List.of(), List.of()));

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("Approval queue"));
    }

    @Test
    void blockedDecisionWithNoReasonIsInvalid() {
        ExecutionDecision blockedNoReason = new ExecutionDecision(WorkflowType.RESUME, ExecutionPolicy.BLOCKED,
                ExecutionPriority.NORMAL, 1, List.of(), List.of(), null, "r");

        ExecutionValidationResult result = validator.validate(
                plan(List.of(blockedNoReason), List.of(), List.of(blockedNoReason), List.of()));

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("no recorded blocking reason"));
    }

    @Test
    void retryQueueContainingWrongPolicyIsInvalid() {
        ExecutionDecision autoDecision = decision(WorkflowType.RESUME, ExecutionPolicy.AUTO);

        ExecutionValidationResult result = validator.validate(
                plan(List.of(autoDecision), List.of(), List.of(), List.of(autoDecision)));

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("Retry queue"));
    }
}
