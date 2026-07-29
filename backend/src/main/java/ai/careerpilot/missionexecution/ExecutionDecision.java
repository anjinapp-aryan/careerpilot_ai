package ai.careerpilot.missionexecution;

import ai.careerpilot.workflowplanner.WorkflowType;

import java.util.List;

/**
 * Pre-Phase-9 Hardening — one {@link WorkflowType}'s execution decision: {@link #policy} (what
 * to do), {@link #priority}, {@link #weekNumber} (its slot in the mission's execution timeline),
 * why it's blocked (if it is), and its {@link ExpectedOutcome}. This is a decision, not an
 * action — nothing in this package acts on it.
 */
public record ExecutionDecision(WorkflowType workflowType, ExecutionPolicy policy, ExecutionPriority priority,
                                 int weekNumber, List<WorkflowType> blockedByWorkflows,
                                 List<Precondition> unmetPreconditions, ExpectedOutcome expectedOutcome,
                                 String reason) {
}
