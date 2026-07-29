package ai.careerpilot.workflowplanner;

import java.util.List;

/**
 * Phase 8 — produces a {@link WorkflowEstimate} for a workflow's full step list. Deterministic,
 * rule-based (no LLM), same discipline as {@code StrategyEvaluationService}/{@code
 * CountryMatchingCapability} elsewhere in this codebase — estimates only, never measured.
 */
public interface WorkflowEstimator {

    WorkflowEstimate estimate(WorkflowType type, List<WorkflowStep> allSteps, WorkflowPriority priority);
}
