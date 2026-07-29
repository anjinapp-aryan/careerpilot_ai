package ai.careerpilot.workflowplanner;

import ai.careerpilot.capability.CapabilityType;

import java.time.Duration;
import java.util.List;

/**
 * Phase 8 — one ordered step of a {@link WorkflowPlan}. {@code capability} reuses the existing
 * {@code ai.careerpilot.capability.CapabilityType} enum (Phase 10.3) rather than introducing a
 * parallel taxonomy; it is {@code null} when a step has no natural mapping (e.g. a plain data
 * lookup step). {@code dependencies} holds the {@link #stepNumber} values that must complete
 * first — validated for existence (not cycles beyond simple self-reference) by
 * {@link WorkflowValidator}. {@code futureLangGraphNode} is a name hint for a future LangGraph
 * node, unused by anything today.
 */
public record WorkflowStep(
        int stepNumber,
        String stepName,
        String description,
        CapabilityType capability,
        List<String> requiredInputs,
        List<String> expectedOutputs,
        int retryCount,
        Duration timeout,
        boolean approvalRequired,
        List<Integer> dependencies,
        boolean canExecuteInParallel,
        Duration estimatedDuration,
        String futureLangGraphNode) {
}
