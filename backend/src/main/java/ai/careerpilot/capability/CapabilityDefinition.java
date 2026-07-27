package ai.careerpilot.capability;

import ai.careerpilot.mcp.McpCapability;

import java.util.Set;

/**
 * Phase 10.3 — declares which {@link McpCapability} categories a {@link CapabilityType} needs.
 * {@link ToolSelectionEngine} resolves this into concrete {@code McpToolDefinition}s by asking
 * {@code McpRegistry.toolsByCapability(...)} for each entry — capabilities are registered, not
 * hardcoded tool names, so a future MCP server that registers under an existing {@link
 * McpCapability} is picked up automatically without touching this class.
 *
 * @param type                 the capability this definition describes
 * @param description          human-readable, e.g. for logging/diagnostics
 * @param requiredMcpCapabilities the MCP capability categories this capability draws context from
 */
public record CapabilityDefinition(CapabilityType type, String description, Set<McpCapability> requiredMcpCapabilities) {
}
