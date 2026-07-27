package ai.careerpilot.mcp.security;

import ai.careerpilot.mcp.McpExecutionContext;
import ai.careerpilot.mcp.McpToolDefinition;
import ai.careerpilot.mcp.McpToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Phase 10.2 — the first real {@link McpAuditor}. Logs only (no new persistence) — deliberately
 * does NOT write to the existing {@code audit_logs} table (see CLAUDE.md's
 * "Provisioned-but-unused" list: that table has no writer today, and wiring one is a decision
 * for whichever future phase actually turns audit_logs on, not an incidental side effect of
 * this one). Matches the "reuse existing MCP Authentication/Authorization/Audit... do not
 * duplicate security logic" instruction by being the single, shared auditor every MCP server
 * uses — no per-server audit logic exists anywhere.
 */
public class LoggingMcpAuditor implements McpAuditor {

    private static final Logger log = LoggerFactory.getLogger(LoggingMcpAuditor.class);

    @Override
    public void record(McpToolDefinition tool, McpExecutionContext context, McpToolResult result) {
        log.info("MCP_AUDIT tool={} server={} user={} success={}",
                tool.toolName(), tool.serverName(),
                context == null ? null : context.userId(),
                result.success());
    }
}
