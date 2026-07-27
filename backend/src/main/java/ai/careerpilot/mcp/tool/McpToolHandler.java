package ai.careerpilot.mcp.tool;

import ai.careerpilot.mcp.McpExecutionContext;

import java.util.Map;

/**
 * Phase 10.2 — the function a registered MCP server plugs in per tool. Synchronous and
 * exception-permitting on purpose: {@link DefaultMcpExecutor} is the single place that catches
 * anything a handler throws and converts it into a graceful {@code McpToolResult.failed(...)}
 * (see its javadoc) — handlers themselves don't need their own try/catch boilerplate.
 */
@FunctionalInterface
public interface McpToolHandler {

    Object handle(Map<String, Object> arguments, McpExecutionContext context);
}
