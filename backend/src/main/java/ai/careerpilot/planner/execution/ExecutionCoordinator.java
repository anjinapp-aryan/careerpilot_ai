package ai.careerpilot.planner.execution;

import ai.careerpilot.mcp.McpExecutionContext;
import ai.careerpilot.planner.CapabilityPlan;

/** Phase 11.3 — executes a whole {@link CapabilityPlan}, stage by stage, and merges the results. */
public interface ExecutionCoordinator {

    MultiCapabilityResult execute(CapabilityPlan plan, McpExecutionContext context);
}
