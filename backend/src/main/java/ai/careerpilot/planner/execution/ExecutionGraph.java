package ai.careerpilot.planner.execution;

import ai.careerpilot.capability.CapabilityType;
import ai.careerpilot.planner.CapabilityPlan;
import ai.careerpilot.planner.CapabilityStep;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 11.3 — a {@link CapabilityPlan}'s {@code ExecutionOrder} (stages of bare {@code
 * CapabilityType}s) re-resolved back into stages of full {@link CapabilityStep}s (type +
 * priority), the shape {@link ExecutionCoordinator} actually walks. Kept as a distinct type from
 * {@code ai.careerpilot.planner.ExecutionOrder} rather than reusing it directly, since a plan's
 * {@code ExecutionOrder} is planning output (bare types) and this is execution input (steps a
 * {@link CapabilityExecutor} can act on) — same data, different layer, same separation of
 * concerns discipline as {@code ExecutionResult} vs. {@code McpToolResult}.
 */
public record ExecutionGraph(List<List<CapabilityStep>> stages) {

    public static ExecutionGraph from(CapabilityPlan plan) {
        Map<CapabilityType, CapabilityStep> stepByType = new HashMap<>();
        for (CapabilityStep step : plan.steps()) {
            stepByType.put(step.type(), step);
        }
        List<List<CapabilityStep>> stages = plan.executionOrder().stages().stream()
                .map(stage -> stage.stream().map(stepByType::get).filter(java.util.Objects::nonNull).toList())
                .filter(stage -> !stage.isEmpty())
                .toList();
        return new ExecutionGraph(stages);
    }

    public boolean isEmpty() {
        return stages.isEmpty();
    }
}
