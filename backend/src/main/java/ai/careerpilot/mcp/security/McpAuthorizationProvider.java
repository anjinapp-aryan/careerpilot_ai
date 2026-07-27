package ai.careerpilot.mcp.security;

import ai.careerpilot.mcp.McpExecutionContext;
import ai.careerpilot.mcp.McpToolDefinition;

/**
 * Phase 10.1 — extension-point only. The contract a future authorization layer would implement
 * to decide whether the caller identified by {@link McpExecutionContext} (matching this
 * codebase's existing {@code userId}/{@code orgId} multi-tenant convention — see CLAUDE.md,
 * "JWT auth carries multi-tenant context") may invoke a given {@link McpToolDefinition}. No
 * implementation exists in this phase.
 */
public interface McpAuthorizationProvider {

    boolean authorize(McpToolDefinition tool, McpExecutionContext context);
}
