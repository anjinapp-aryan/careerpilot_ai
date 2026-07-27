package ai.careerpilot.mcp.security;

import ai.careerpilot.mcp.McpExecutionContext;
import ai.careerpilot.mcp.McpServerDefinition;

/**
 * Phase 10.2 — the first real {@link McpAuthenticationProvider}. Always returns {@code true}:
 * of the five Phase 10.2 MCP servers, none require this hook to do real work today — the
 * PostgreSQL/Filesystem/Memory servers read internal data already gated by Spring Security's
 * JWT filter (the caller is already authenticated by the time an {@link McpExecutionContext}
 * exists), GitHub's client is keyless, and Context7's client is API-key-gated via its own {@code
 * isConfigured()} check (matching the same-shaped convention as every {@code LlmProvider}/{@code
 * JobProvider} in this codebase — see {@code ai.careerpilot.mcp.tool.context7.Context7ApiClient}).
 * This hook exists for a future MCP server whose *external* authentication (e.g. an OAuth2
 * token exchange) can't be expressed as a simple configured-or-not client check.
 */
public class DefaultMcpAuthenticationProvider implements McpAuthenticationProvider {

    @Override
    public boolean authenticate(McpServerDefinition server, McpExecutionContext context) {
        return true;
    }
}
