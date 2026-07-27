package ai.careerpilot.mcp.tool;

import ai.careerpilot.mcp.InMemoryMcpMetrics;
import ai.careerpilot.mcp.McpCapability;
import ai.careerpilot.mcp.McpExecutionContext;
import ai.careerpilot.mcp.McpHealthManager;
import ai.careerpilot.mcp.McpHealthStatus;
import ai.careerpilot.mcp.McpToolDefinition;
import ai.careerpilot.mcp.McpToolResult;
import ai.careerpilot.mcp.security.LoggingMcpAuditor;
import ai.careerpilot.mcp.security.McpAuditor;
import ai.careerpilot.mcp.security.McpAuthorizationProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DefaultMcpExecutor} is the platform's one real {@code McpExecutor} — this checks its
 * "never throw, always degrade gracefully" contract: a successful handler returns
 * {@code McpToolResult.ok}, a missing handler / throwing handler / unauthorized call all return
 * {@code McpToolResult.failed} rather than propagating, and metrics + health are recorded either
 * way.
 */
class DefaultMcpExecutorTest {

    private final McpToolHandlerRegistry handlers = new McpToolHandlerRegistry();
    private final McpAuditor auditor = new LoggingMcpAuditor();
    private final InMemoryMcpMetrics metrics = new InMemoryMcpMetrics();

    private McpToolDefinition tool(String name) {
        return new McpToolDefinition(name, "desc", Map.of(), Map.of(), McpCapability.DATABASE, "test-server");
    }

    private McpExecutionContext context() {
        return new McpExecutionContext(UUID.randomUUID(), null, null, "trace-1", Duration.ofSeconds(5), Map.of());
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<McpHealthManager> noHealthManager() {
        ObjectProvider<McpHealthManager> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    @Test
    void successfulHandlerReturnsOkResult() {
        McpAuthorizationProvider alwaysAuthorized = (t, ctx) -> true;
        DefaultMcpExecutor executor = new DefaultMcpExecutor(handlers, alwaysAuthorized, auditor, metrics, noHealthManager());
        McpToolDefinition tool = tool("echo");
        handlers.register("echo", (args, ctx) -> "hello");

        McpToolResult result = executor.execute(tool, Map.of(), context()).block();

        assertThat(result.success()).isTrue();
        assertThat(result.output()).isEqualTo("hello");
        assertThat(result.error()).isNull();
    }

    @Test
    void missingHandlerReturnsFailedResultRatherThanThrowing() {
        McpAuthorizationProvider alwaysAuthorized = (t, ctx) -> true;
        DefaultMcpExecutor executor = new DefaultMcpExecutor(handlers, alwaysAuthorized, auditor, metrics, noHealthManager());
        McpToolDefinition tool = tool("no_handler_registered");

        McpToolResult result = executor.execute(tool, Map.of(), context()).block();

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("no handler registered");
    }

    @Test
    void throwingHandlerReturnsFailedResultRatherThanPropagating() {
        McpAuthorizationProvider alwaysAuthorized = (t, ctx) -> true;
        DefaultMcpExecutor executor = new DefaultMcpExecutor(handlers, alwaysAuthorized, auditor, metrics, noHealthManager());
        McpToolDefinition tool = tool("boom");
        handlers.register("boom", (args, ctx) -> { throw new RuntimeException("db down"); });

        McpToolResult result = executor.execute(tool, Map.of(), context()).block();

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("db down");
    }

    @Test
    void unauthorizedCallNeverInvokesHandler() {
        McpAuthorizationProvider neverAuthorized = (t, ctx) -> false;
        DefaultMcpExecutor executor = new DefaultMcpExecutor(handlers, neverAuthorized, auditor, metrics, noHealthManager());
        McpToolDefinition tool = tool("sensitive");
        boolean[] invoked = {false};
        handlers.register("sensitive", (args, ctx) -> { invoked[0] = true; return "should not run"; });

        McpToolResult result = executor.execute(tool, Map.of(), context()).block();

        assertThat(result.success()).isFalse();
        assertThat(result.error()).isEqualTo("not authorized");
        assertThat(invoked[0]).isFalse();
    }

    @Test
    void recordsMetricsForBothSuccessAndFailure() {
        McpAuthorizationProvider alwaysAuthorized = (t, ctx) -> true;
        DefaultMcpExecutor executor = new DefaultMcpExecutor(handlers, alwaysAuthorized, auditor, metrics, noHealthManager());
        handlers.register("ok_tool", (args, ctx) -> "fine");
        handlers.register("bad_tool", (args, ctx) -> { throw new RuntimeException("nope"); });

        executor.execute(tool("ok_tool"), Map.of(), context()).block();
        executor.execute(tool("bad_tool"), Map.of(), context()).block();

        assertThat(metrics.successCount("ok_tool")).isEqualTo(1);
        assertThat(metrics.failureCount("bad_tool")).isEqualTo(1);
    }

    @Test
    void recordsHeartbeatOnHealthManagerWhenPresent() {
        McpAuthorizationProvider alwaysAuthorized = (t, ctx) -> true;
        McpHealthManager healthManager = mock(McpHealthManager.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<McpHealthManager> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(healthManager);

        DefaultMcpExecutor executor = new DefaultMcpExecutor(handlers, alwaysAuthorized, auditor, metrics, provider);
        handlers.register("healthy_tool", (args, ctx) -> "fine");

        executor.execute(tool("healthy_tool"), Map.of(), context()).block();

        verify(healthManager).recordHeartbeat(org.mockito.ArgumentMatchers.eq("test-server"), org.mockito.ArgumentMatchers.eq(McpHealthStatus.UP), anyLong());
    }
}
