package ai.careerpilot.planner.execution;

import ai.careerpilot.capability.CapabilityType;
import ai.careerpilot.mcp.McpExecutionContext;
import ai.careerpilot.planner.CapabilityStep;

import java.util.List;
import java.util.Map;

/**
 * Phase 11.3 — executes one "stage" (a {@code List<CapabilityStep>} known, by construction of
 * {@code ai.careerpilot.planner.PlanOptimizer}, to have no dependency between its own members) in
 * parallel.
 */
public interface ParallelCapabilityExecutor {

    Map<CapabilityType, ExecutionResult> executeStage(List<CapabilityStep> stage, McpExecutionContext context);
}
