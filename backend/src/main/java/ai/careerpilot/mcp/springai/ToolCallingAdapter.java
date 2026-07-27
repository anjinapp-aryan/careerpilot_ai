package ai.careerpilot.mcp.springai;

import ai.careerpilot.mcp.McpExecutionContext;
import ai.careerpilot.mcp.McpToolDefinition;
import org.springframework.ai.tool.ToolCallback;

/**
 * Phase 10.1 — extension-point only. The contract a future integration would implement to turn
 * one {@link McpToolDefinition} into a Spring AI {@link ToolCallback} the {@code ChatModel} can
 * invoke directly, delegating the actual call to a future {@link
 * ai.careerpilot.mcp.McpExecutor}. No implementation exists in this phase — this is the seam
 * {@link SpringAiMcpBridge} would use once real MCP tools exist to hand off to.
 */
public interface ToolCallingAdapter {

    ToolCallback adapt(McpToolDefinition tool, McpExecutionContext context);
}
