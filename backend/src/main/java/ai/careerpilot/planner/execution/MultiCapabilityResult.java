package ai.careerpilot.planner.execution;

import ai.careerpilot.capability.CapabilityType;
import ai.careerpilot.intent.IntentType;

import java.util.Map;

/**
 * Phase 11.3 — {@link ExecutionCoordinator}'s full verdict for one {@code CapabilityPlan}:
 * every capability's individual {@link ExecutionResult} (partial failures included — a failed
 * capability never prevents the others from running or being reported), the {@link
 * MergedExecutionContext}, and whether every capability succeeded. Consuming this (e.g.
 * synthesizing a final answer from {@code mergedContext}) is out of scope for Phase 11.3 —
 * nothing calls {@link ExecutionCoordinator} yet.
 */
public record MultiCapabilityResult(IntentType intentType, Map<CapabilityType, ExecutionResult> results,
                                     MergedExecutionContext mergedContext, boolean allSucceeded,
                                     long totalLatencyMs, String reason) {

    public static MultiCapabilityResult empty(String reason) {
        return new MultiCapabilityResult(null, Map.of(), MergedExecutionContext.empty(), true, 0, reason);
    }
}
