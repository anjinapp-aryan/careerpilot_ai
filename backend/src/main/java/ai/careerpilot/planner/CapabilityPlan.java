package ai.careerpilot.planner;

import ai.careerpilot.intent.IntentType;

import java.util.List;

/**
 * Phase 11.2 — the {@link CapabilityPlanner}'s full output for one request: which capabilities
 * are needed, their dependency graph, and the resolved parallel-safe {@link ExecutionOrder}.
 * {@code intentType} is {@code null} and {@code steps}/{@code executionOrder} are empty when the
 * upstream {@code IntentResult} had no matched intent — the same "empty plan means fall back to
 * whatever the caller already does" convention used throughout Phase 10/11.
 *
 * @param intentType     the intent this plan was built for, or {@code null}
 * @param steps          every capability this plan requires, unordered
 * @param dependencies   the dependency graph among {@code steps}
 * @param executionOrder the resolved stage-by-stage order ({@link PlanOptimizer} output)
 * @param reason         human-readable explanation, always populated
 */
public record CapabilityPlan(IntentType intentType, List<CapabilityStep> steps,
                              CapabilityDependencies dependencies, ExecutionOrder executionOrder,
                              String reason) {

    public static CapabilityPlan empty(String reason) {
        return new CapabilityPlan(null, List.of(), CapabilityDependencies.none(), ExecutionOrder.empty(), reason);
    }

    public boolean isEmpty() {
        return steps.isEmpty();
    }
}
