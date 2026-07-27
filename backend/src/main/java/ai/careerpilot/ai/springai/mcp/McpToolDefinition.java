package ai.careerpilot.ai.springai.mcp;

import java.util.Map;

/**
 * Phase 9.1 — extension-point only. Describes a tool a future MCP server would
 * expose (name, human-readable description, capability category, and a JSON-Schema-
 * shaped {@code inputSchema}). No executable behavior — this is metadata a future
 * {@link McpToolRegistry} implementation would hold, not a callable tool.
 */
public record McpToolDefinition(
        String name,
        String description,
        McpCapability capability,
        Map<String, Object> inputSchema) {
}
