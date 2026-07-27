package ai.careerpilot.mcp.springai;

import ai.careerpilot.mcp.McpCapability;
import ai.careerpilot.mcp.McpExecutionContext;
import ai.careerpilot.mcp.McpExecutor;
import ai.careerpilot.mcp.McpToolDefinition;
import ai.careerpilot.mcp.McpToolResult;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DefaultToolCallingAdapter} turns an {@link McpToolDefinition} into a real Spring AI
 * {@link ToolCallback}. Verifies the callback's metadata (name/description carried through) and
 * that invoking it actually calls {@link McpExecutor#execute} — proving the "Spring AI Tool
 * Calling" wiring genuinely dispatches into the MCP platform rather than being a stub.
 */
class DefaultToolCallingAdapterTest {

    @Test
    void adaptedCallbackExposesToolNameAndDescription() {
        McpExecutor executor = (tool, args, ctx) -> Mono.just(McpToolResult.ok("ignored"));
        DefaultToolCallingAdapter adapter = new DefaultToolCallingAdapter(executor);
        McpToolDefinition tool = new McpToolDefinition("get_job_recommendations", "returns job recs", Map.of(), Map.of(), McpCapability.DATABASE, "postgres");
        McpExecutionContext context = new McpExecutionContext(UUID.randomUUID(), null, null, "trace", Duration.ofSeconds(5), Map.of());

        ToolCallback callback = adapter.adapt(tool, context);

        assertThat(callback.getToolDefinition().name()).isEqualTo("get_job_recommendations");
        assertThat(callback.getToolDefinition().description()).isEqualTo("returns job recs");
    }

    @Test
    void invokingCallbackDelegatesToExecutorAndReturnsItsOutput() {
        boolean[] executed = {false};
        McpExecutor executor = (tool, args, ctx) -> {
            executed[0] = true;
            return Mono.just(McpToolResult.ok(Map.of("count", 3)));
        };
        DefaultToolCallingAdapter adapter = new DefaultToolCallingAdapter(executor);
        McpToolDefinition tool = new McpToolDefinition("get_job_recommendations", "desc", Map.of(), Map.of(), McpCapability.DATABASE, "postgres");
        McpExecutionContext context = new McpExecutionContext(UUID.randomUUID(), null, null, "trace", Duration.ofSeconds(5), Map.of());

        ToolCallback callback = adapter.adapt(tool, context);
        String result = callback.call("{}");

        assertThat(executed[0]).isTrue();
        assertThat(result).contains("count");
    }
}
