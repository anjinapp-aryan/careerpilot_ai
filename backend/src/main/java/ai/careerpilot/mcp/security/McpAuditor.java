package ai.careerpilot.mcp.security;

import ai.careerpilot.mcp.McpExecutionContext;
import ai.careerpilot.mcp.McpToolDefinition;
import ai.careerpilot.mcp.McpToolResult;

/**
 * Phase 10.1 — extension-point only. The contract a future audit layer would implement to
 * record every MCP tool invocation attempt. Deliberately separate from {@code
 * ai.careerpilot.audit_logs} (the existing provisioned-but-unused audit table documented in
 * CLAUDE.md) — wiring this to that table, or any table, is left to whichever future phase adds
 * the first real {@link ai.careerpilot.mcp.McpExecutor} implementation. No implementation
 * exists in this phase.
 */
public interface McpAuditor {

    void record(McpToolDefinition tool, McpExecutionContext context, McpToolResult result);
}
