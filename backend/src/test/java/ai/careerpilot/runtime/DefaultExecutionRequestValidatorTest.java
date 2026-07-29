package ai.careerpilot.runtime;

import ai.careerpilot.missionexecution.ExecutionDecision;
import ai.careerpilot.missionexecution.ExecutionPolicy;
import ai.careerpilot.missionexecution.ExecutionPriority;
import ai.careerpilot.workflowplanner.WorkflowType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultExecutionRequestValidatorTest {

    private final DefaultExecutionRequestValidator validator = new DefaultExecutionRequestValidator();

    private ExecutionDecision decision(ExecutionPolicy policy) {
        return new ExecutionDecision(WorkflowType.RESUME, policy, ExecutionPriority.NORMAL, 1,
                List.of(), List.of(), null, "r");
    }

    private WorkflowExecutionRequest request(ExecutionDecision decision) {
        return WorkflowExecutionRequest.forDecision(UUID.randomUUID(), UUID.randomUUID(), decision, Map.of(), "corr-1");
    }

    @Test
    void validAutoDecisionPasses() {
        assertThat(validator.validate(request(decision(ExecutionPolicy.AUTO)))).isEmpty();
    }

    @Test
    void nullRequestIsRejected() {
        assertThat(validator.validate(null)).isNotEmpty();
    }

    @Test
    void missingMissionIdIsRejected() {
        WorkflowExecutionRequest request = new WorkflowExecutionRequest(null, UUID.randomUUID(),
                "RESUME_OPTIMIZATION", decision(ExecutionPolicy.AUTO), Map.of(), "corr-1");

        assertThat(validator.validate(request)).anyMatch(v -> v.contains("missionId"));
    }

    @Test
    void missingUserIdIsRejected() {
        WorkflowExecutionRequest request = new WorkflowExecutionRequest(UUID.randomUUID(), null,
                "RESUME_OPTIMIZATION", decision(ExecutionPolicy.AUTO), Map.of(), "corr-1");

        assertThat(validator.validate(request)).anyMatch(v -> v.contains("userId"));
    }

    @Test
    void missingWorkflowIdIsRejected() {
        WorkflowExecutionRequest request = new WorkflowExecutionRequest(UUID.randomUUID(), UUID.randomUUID(),
                "", decision(ExecutionPolicy.AUTO), Map.of(), "corr-1");

        assertThat(validator.validate(request)).anyMatch(v -> v.contains("workflowId"));
    }

    @Test
    void anAdHocRequestWithNoExecutionDecisionIsValid() {
        // Phase 10A: executionDecision is optional — a generically-dispatched request needs only
        // a workflowId, no Mission Execution Engine decision.
        WorkflowExecutionRequest request = new WorkflowExecutionRequest(UUID.randomUUID(), UUID.randomUUID(),
                "SKILL_GAP_INTELLIGENCE", null, Map.of(), "corr-1");

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void blockedPolicyIsRejected() {
        assertThat(validator.validate(request(decision(ExecutionPolicy.BLOCKED)))).isNotEmpty();
    }

    @Test
    void approvalRequiredPolicyIsRejectedSinceNoApprovalManagerExistsYet() {
        assertThat(validator.validate(request(decision(ExecutionPolicy.APPROVAL_REQUIRED)))).isNotEmpty();
    }

    @Test
    void cancelledSkippedAndWaitPoliciesAreRejected() {
        assertThat(validator.validate(request(decision(ExecutionPolicy.CANCELLED)))).isNotEmpty();
        assertThat(validator.validate(request(decision(ExecutionPolicy.SKIPPED)))).isNotEmpty();
        assertThat(validator.validate(request(decision(ExecutionPolicy.WAIT)))).isNotEmpty();
    }

    @Test
    void sequentialParallelAndRetryPoliciesAreRunnable() {
        assertThat(validator.validate(request(decision(ExecutionPolicy.SEQUENTIAL)))).isEmpty();
        assertThat(validator.validate(request(decision(ExecutionPolicy.PARALLEL)))).isEmpty();
        assertThat(validator.validate(request(decision(ExecutionPolicy.RETRY)))).isEmpty();
    }
}
