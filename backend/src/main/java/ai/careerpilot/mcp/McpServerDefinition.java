package ai.careerpilot.mcp;

import java.util.Set;

/**
 * Phase 10.1 — metadata describing one MCP server a plugin registers with {@link McpRegistry}.
 * This is a pure data record: registering a definition does not open a connection, spawn a
 * process, or contact anything — {@link McpExecutor} (not yet implemented by anything) is what
 * would eventually do that. Live health is tracked separately by {@link McpHealthManager},
 * keyed by {@link #name()}, rather than embedded here, since health changes over time and this
 * record does not.
 *
 * @param name           unique registry key, e.g. {@code "filesystem"}, {@code "postgres"}
 * @param version        the server's own version string, informational only
 * @param capabilities   the {@link McpCapability} categories this server claims to expose
 * @param enabled        whether this server is currently eligible for lookup/execution — a
 *                        disabled definition can still be registered (for visibility/audit) but
 *                        {@link McpRegistry} implementations should exclude it from active lookups
 * @param priority       lower runs first when multiple servers expose the same capability;
 *                        mirrors the existing provider-priority convention in {@code ai.gateway.order}
 * @param authentication how this server authenticates, metadata only — see {@link McpAuthenticationMode}
 */
public record McpServerDefinition(
        String name,
        String version,
        Set<McpCapability> capabilities,
        boolean enabled,
        int priority,
        McpAuthenticationMode authentication) {
}
