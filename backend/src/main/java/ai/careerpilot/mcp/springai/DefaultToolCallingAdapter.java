package ai.careerpilot.mcp.springai;

import ai.careerpilot.mcp.McpExecutionContext;
import ai.careerpilot.mcp.McpExecutor;
import ai.careerpilot.mcp.McpToolDefinition;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;

import java.util.Map;

/**
 * Phase 10.2 — the first real {@link ToolCallingAdapter}. Turns one {@link McpToolDefinition}
 * into a Spring AI {@link ToolCallback} backed by {@link org.springframework.ai.tool.function.FunctionToolCallback}
 * (from {@code spring-ai-model}, already on the classpath since Phase 9.1's {@code
 * spring-ai-bom} import — no new dependency). Calling the resulting {@code ToolCallback} invokes
 * {@link McpExecutor#execute} synchronously (blocking on the returned {@code Mono} — Spring
 * AI's {@code ToolCallback.call(String)} contract is itself synchronous) with the {@link
 * McpExecutionContext} this adapter was built with. Nothing in {@code AiGatewayService}, the
 * Smart Router, or any {@code ChatModel} bean constructs or calls a {@code ToolCallback} built
 * this way — see {@link DefaultSpringAiMcpBridge}, the only current caller.
 */
public class DefaultToolCallingAdapter implements ToolCallingAdapter {

    private final McpExecutor executor;

    public DefaultToolCallingAdapter(McpExecutor executor) {
        this.executor = executor;
    }

    @Override
    public ToolCallback adapt(McpToolDefinition tool, McpExecutionContext context) {
        return FunctionToolCallback.<Map<String, Object>, Object>builder(tool.toolName(),
                        (Map<String, Object> args) -> executor.execute(tool, args, context).block())
                .description(tool.description())
                .inputType(Map.class)
                .build();
    }
}
