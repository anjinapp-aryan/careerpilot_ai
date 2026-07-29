package ai.careerpilot.missionexecution;

import ai.careerpilot.workflowplanner.WorkflowType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Pre-Phase-9 Hardening — the only {@link ExecutionValidator}: no duplicate workflow types across
 * decisions, every queue only contains decisions with the matching policy, every blocked
 * decision carries at least one reason it's blocked.
 */
public class DefaultExecutionValidator implements ExecutionValidator {

    @Override
    public ExecutionValidationResult validate(MissionExecutionPlan plan) {
        List<String> errors = new ArrayList<>();

        Set<WorkflowType> seen = new HashSet<>();
        for (ExecutionDecision d : plan.decisions()) {
            if (!seen.add(d.workflowType())) {
                errors.add("Duplicate execution decision for workflow type " + d.workflowType());
            }
        }

        for (ExecutionDecision d : plan.approvalQueue()) {
            if (d.policy() != ExecutionPolicy.APPROVAL_REQUIRED) {
                errors.add("Approval queue contains a decision not policy APPROVAL_REQUIRED: " + d.workflowType());
            }
        }
        for (ExecutionDecision d : plan.blockedWorkflows()) {
            if (d.policy() != ExecutionPolicy.BLOCKED) {
                errors.add("Blocked-workflow list contains a decision not policy BLOCKED: " + d.workflowType());
            }
            if (d.blockedByWorkflows().isEmpty() && d.unmetPreconditions().isEmpty()) {
                errors.add("Blocked decision for " + d.workflowType() + " has no recorded blocking reason.");
            }
        }
        for (ExecutionDecision d : plan.retryQueue()) {
            if (d.policy() != ExecutionPolicy.RETRY) {
                errors.add("Retry queue contains a decision not policy RETRY: " + d.workflowType());
            }
        }

        return errors.isEmpty() ? ExecutionValidationResult.ok() : ExecutionValidationResult.invalid(errors);
    }
}
