package ai.careerpilot.workflowplanner;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Phase 8 — the only {@link WorkflowValidator}: at least one step total, unique step numbers,
 * every dependency references a real step number, no step depends on itself.
 */
public class DefaultWorkflowValidator implements WorkflowValidator {

    @Override
    public WorkflowValidationResult validate(WorkflowPlan plan) {
        List<WorkflowStep> allSteps = plan.allSteps();
        List<String> errors = new ArrayList<>();

        if (allSteps.isEmpty()) {
            errors.add("Workflow plan has no steps.");
            return WorkflowValidationResult.invalid(errors);
        }

        Set<Integer> stepNumbers = allSteps.stream().map(WorkflowStep::stepNumber).collect(Collectors.toSet());
        if (stepNumbers.size() != allSteps.size()) {
            errors.add("Duplicate step numbers detected.");
        }

        for (WorkflowStep step : allSteps) {
            for (Integer dep : step.dependencies()) {
                if (dep.equals(step.stepNumber())) {
                    errors.add("Step " + step.stepNumber() + " depends on itself.");
                } else if (!stepNumbers.contains(dep)) {
                    errors.add("Step " + step.stepNumber() + " depends on unknown step " + dep + ".");
                }
            }
        }

        return errors.isEmpty() ? WorkflowValidationResult.ok() : WorkflowValidationResult.invalid(errors);
    }
}
