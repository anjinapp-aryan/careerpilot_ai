package ai.careerpilot.mcp.security;

import ai.careerpilot.mcp.McpExecutionContext;
import ai.careerpilot.mcp.McpServerDefinition;

/**
 * Phase 10.1 — extension-point only. The contract a future MCP server integration would use to
 * authenticate against its target system, keyed off {@link McpServerDefinition#authentication()}.
 * No implementation exists — every future MCP server is expected to inherit this abstraction
 * automatically rather than rolling its own credential handling, per the phase spec's "Future
 * MCP servers must inherit these automatically."
 */
public interface McpAuthenticationProvider {

    boolean authenticate(McpServerDefinition server, McpExecutionContext context);
}
