/**
 * Phase 10.1 — MCP security abstractions: {@link ai.careerpilot.mcp.security.McpAuthenticationProvider},
 * {@link ai.careerpilot.mcp.security.McpAuthorizationProvider}, {@link ai.careerpilot.mcp.security.McpAuditor}.
 * Interfaces only, no implementation, no Spring bean. Exists so that when a future phase adds
 * the first real MCP server integration, it inherits authentication/authorization/audit by
 * depending on these contracts rather than each server integration reinventing its own.
 */
package ai.careerpilot.mcp.security;
