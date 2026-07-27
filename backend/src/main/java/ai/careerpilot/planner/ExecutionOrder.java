package ai.careerpilot.planner;

import ai.careerpilot.capability.CapabilityType;

import java.util.List;

/**
 * Phase 11.2 — the resolved execution order for a {@link CapabilityPlan}: a list of "stages,"
 * each a list of {@link CapabilityType}s with no dependency between them (safe to execute in
 * parallel within a stage), stages themselves ordered so every dependency of a capability in
 * stage N is satisfied by stage N-1 or earlier. Produced by {@link PlanOptimizer}. This is
 * planning output only — Phase 11.2 does not execute anything; a future Phase 11.3 {@code
 * CapabilityExecutor}/{@code ParallelCapabilityExecutor} is what would consume this shape.
 */
public record ExecutionOrder(List<List<CapabilityType>> stages) {

    public static ExecutionOrder empty() {
        return new ExecutionOrder(List.of());
    }

    public int stageCount() {
        return stages.size();
    }
}
