package ai.careerpilot.mcp.springai;

import ai.careerpilot.mcp.McpExecutionContext;
import ai.careerpilot.mcp.McpToolDefinition;

import java.util.List;

/**
 * Phase 10.1 — extension-point only. The contract a future integration would implement to
 * decide, for a given call context, which {@link ai.careerpilot.mcp.McpToolDefinition}s a Spring
 * AI {@code ChatModel} call should be offered (analogous to Spring AI's own tool-resolution
 * concept, but scoped to this platform's registered MCP tools). No implementation exists; not
 * consulted by {@link ai.careerpilot.ai.springai.SpringAiConfig} or any provider.
 */
public interface McpToolResolver {

    List<McpToolDefinition> resolve(McpExecutionContext context);
}
