package ai.careerpilot.planner.execution;

import ai.careerpilot.mcp.McpExecutionContext;
import ai.careerpilot.planner.CapabilityStep;

/** Phase 11.3 — executes one {@link CapabilityStep}, resolving and running its underlying MCP tools. */
public interface CapabilityExecutor {

    ExecutionResult execute(CapabilityStep step, McpExecutionContext context);
}
