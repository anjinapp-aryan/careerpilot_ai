package ai.careerpilot.mcp;

import java.util.Map;

/**
 * Phase 10.1 — metadata describing a single tool exposed by a registered {@link
 * McpServerDefinition}. {@code inputSchema}/{@code outputSchema} are plain JSON-Schema-shaped
 * maps (same convention as every other JSON-schema map already in this codebase, e.g. the
 * Python agent-service's {@code responseSchema} usage) rather than a typed schema class, so
 * this stays server-agnostic. No executable behavior lives here — {@link McpExecutor} is the
 * (currently unimplemented) contract that would actually invoke a tool matching this definition.
 *
 * @param toolName      unique-per-server tool name
 * @param description   human-readable description, e.g. for LLM tool-selection prompts
 * @param inputSchema   JSON-Schema-shaped map describing expected arguments
 * @param outputSchema  JSON-Schema-shaped map describing the tool's result shape
 * @param capability    which {@link McpCapability} this tool falls under
 * @param serverName    the owning {@link McpServerDefinition#name()}
 */
public record McpToolDefinition(
        String toolName,
        String description,
        Map<String, Object> inputSchema,
        Map<String, Object> outputSchema,
        McpCapability capability,
        String serverName) {
}
