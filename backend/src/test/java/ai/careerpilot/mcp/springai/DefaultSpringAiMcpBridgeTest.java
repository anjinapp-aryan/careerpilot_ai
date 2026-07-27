package ai.careerpilot.mcp.springai;

import ai.careerpilot.mcp.InMemoryMcpRegistry;
import ai.careerpilot.mcp.McpCapability;
import ai.careerpilot.mcp.McpExecutor;
import ai.careerpilot.mcp.McpToolDefinition;
import ai.careerpilot.mcp.McpToolResult;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DefaultSpringAiMcpBridge} — verifies it resolves registered tools scoped to a
 * capability into real {@link ToolCallback}s, and correctly excludes tools of a different
 * capability.
 */
class DefaultSpringAiMcpBridgeTest {

    @Test
    void resolvesOnlyToolsMatchingRequestedCapability() {
        InMemoryMcpRegistry registry = new InMemoryMcpRegistry();
        registry.registerTool(new McpToolDefinition("get_job_recommendations", "d", Map.of(), Map.of(), McpCapability.DATABASE, "postgres"));
        registry.registerTool(new McpToolDefinition("analyze_github_profile", "d", Map.of(), Map.of(), McpCapability.GITHUB, "github"));

        McpExecutor executor = (tool, args, ctx) -> Mono.just(McpToolResult.ok("x"));
        DefaultSpringAiMcpBridge bridge = new DefaultSpringAiMcpBridge(registry, new DefaultToolCallingAdapter(executor));

        List<ToolCallback> databaseCallbacks = bridge.toolCallbacksFor(McpCapability.DATABASE);

        assertThat(databaseCallbacks).hasSize(1);
        assertThat(databaseCallbacks.get(0).getToolDefinition().name()).isEqualTo("get_job_recommendations");
    }

    @Test
    void returnsEmptyListForCapabilityWithNoRegisteredTools() {
        InMemoryMcpRegistry registry = new InMemoryMcpRegistry();
        McpExecutor executor = (tool, args, ctx) -> Mono.just(McpToolResult.ok("x"));
        DefaultSpringAiMcpBridge bridge = new DefaultSpringAiMcpBridge(registry, new DefaultToolCallingAdapter(executor));

        assertThat(bridge.toolCallbacksFor(McpCapability.MEMORY)).isEmpty();
    }
}
