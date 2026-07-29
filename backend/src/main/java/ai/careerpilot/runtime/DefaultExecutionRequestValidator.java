package ai.careerpilot.runtime;

import ai.careerpilot.missionexecution.ExecutionDecision;
import ai.careerpilot.missionexecution.ExecutionPolicy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * LangGraph Workflow Runtime — structural validation only. {@link WorkflowExecutionRequest#workflowId()}
 * is the primary identity check (Phase 10A: any non-blank registry key, not tied to {@code
 * WorkflowType}). {@link ExecutionDecision} is optional (Phase 10A) — when present (the Mission
 * Execution Engine flow), its policy is still checked against {@link #NON_RUNNABLE_POLICIES}
 * ({@code APPROVAL_REQUIRED} included because no Approval Manager exists yet in this codebase —
 * executing an approval-gated workflow without a real gate would silently bypass a control this
 * platform's other acting subsystems always enforce); when absent (an ad-hoc or generically
 * dispatched run), that check is simply skipped — there is no Mission Execution Engine decision to
 * validate.
 */
public class DefaultExecutionRequestValidator implements ExecutionRequestValidator {

    private static final Set<ExecutionPolicy> NON_RUNNABLE_POLICIES = Set.of(
            ExecutionPolicy.BLOCKED, ExecutionPolicy.CANCELLED, ExecutionPolicy.SKIPPED,
            ExecutionPolicy.WAIT, ExecutionPolicy.APPROVAL_REQUIRED);

    @Override
    public List<String> validate(WorkflowExecutionRequest request) {
        List<String> violations = new ArrayList<>();
        if (request == null) {
            violations.add("Execution request must not be null");
            return violations;
        }
        if (request.missionId() == null) {
            violations.add("missionId is required");
        }
        if (request.userId() == null) {
            violations.add("userId is required");
        }
        if (request.workflowId() == null || request.workflowId().isBlank()) {
            violations.add("workflowId is required");
        }

        ExecutionDecision decision = request.executionDecision();
        if (decision != null) {
            if (decision.policy() == null) {
                violations.add("executionDecision.policy is required when executionDecision is supplied");
            } else if (NON_RUNNABLE_POLICIES.contains(decision.policy())) {
                violations.add("executionDecision.policy=" + decision.policy() + " is not runnable by this runtime "
                        + "(requires an upstream gate — approval, unblocking, or rescheduling — that hasn't happened yet)");
            }
        }
        return violations;
    }
}
