package ai.careerpilot.capability;

import ai.careerpilot.mcp.McpToolDefinition;

import java.util.List;

/** Phase 10.3 — resolves a {@link CapabilityDefinition} into the concrete MCP tools to execute. */
public interface ToolSelectionEngine {

    List<McpToolDefinition> selectTools(CapabilityDefinition definition);
}
