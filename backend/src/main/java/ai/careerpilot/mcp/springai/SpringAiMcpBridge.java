package ai.careerpilot.mcp.springai;

import ai.careerpilot.mcp.McpCapability;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * Phase 10.1 — extension-point only. The contract a future integration would implement to
 * expose registered {@link ai.careerpilot.mcp.McpToolDefinition}s to Spring AI's own tool-calling
 * machinery ({@link org.springframework.ai.tool.ToolCallback}, from the {@code spring-ai-bom}
 * dependency already added in Phase 9.1 — no new dependency needed here). No implementation
 * exists; nothing in {@link ai.careerpilot.ai.springai.SpringAiConfig} or any {@code ChatModel}
 * bean references this interface. Do NOT connect today, per the phase spec.
 */
public interface SpringAiMcpBridge {

    List<ToolCallback> toolCallbacksFor(McpCapability capability);
}
