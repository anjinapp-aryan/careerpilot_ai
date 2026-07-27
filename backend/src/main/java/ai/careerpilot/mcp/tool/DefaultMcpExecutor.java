package ai.careerpilot.mcp.tool;

import ai.careerpilot.mcp.McpExecutionContext;
import ai.careerpilot.mcp.McpExecutor;
import ai.careerpilot.mcp.McpHealthManager;
import ai.careerpilot.mcp.McpHealthStatus;
import ai.careerpilot.mcp.McpMetrics;
import ai.careerpilot.mcp.McpToolDefinition;
import ai.careerpilot.mcp.McpToolResult;
import ai.careerpilot.mcp.security.McpAuditor;
import ai.careerpilot.mcp.security.McpAuthorizationProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Phase 10.2 — the first real {@link McpExecutor} implementation. Every call goes through the
 * same four steps, and every step is designed so a failure degrades gracefully rather than
 * propagating: authorize (via {@link McpAuthorizationProvider}) → dispatch to the tool's
 * registered {@link McpToolHandler} → record metrics/health → audit. Any exception anywhere in
 * that chain (a missing handler, a handler throwing, an unauthorized call) is caught here and
 * converted to {@code McpToolResult.failed(...)} — this class never lets an exception escape
 * {@link #execute}, matching the phase's "never fail the user request, never expose MCP
 * failures to users" requirement. Since nothing on any production request path calls {@link
 * #execute} yet (see the package javadoc), this guarantee is currently enforced defensively
 * for future callers, not exercised by real traffic.
 */
public class DefaultMcpExecutor implements McpExecutor {

    private static final Logger log = LoggerFactory.getLogger(DefaultMcpExecutor.class);

    private final McpToolHandlerRegistry handlers;
    private final McpAuthorizationProvider authorization;
    private final McpAuditor auditor;
    private final McpMetrics metrics;
    private final McpHealthManager healthManager;

    public DefaultMcpExecutor(McpToolHandlerRegistry handlers,
                               McpAuthorizationProvider authorization,
                               McpAuditor auditor,
                               McpMetrics metrics,
                               ObjectProvider<McpHealthManager> healthManagerProvider) {
        this.handlers = handlers;
        this.authorization = authorization;
        this.auditor = auditor;
        this.metrics = metrics;
        this.healthManager = healthManagerProvider.getIfAvailable();
    }

    @Override
    public Mono<McpToolResult> execute(McpToolDefinition tool, Map<String, Object> arguments, McpExecutionContext context) {
        return Mono.fromCallable(() -> doExecute(tool, arguments, context));
    }

    private McpToolResult doExecute(McpToolDefinition tool, Map<String, Object> arguments, McpExecutionContext context) {
        long start = System.currentTimeMillis();
        McpToolResult result;
        try {
            if (!authorization.authorize(tool, context)) {
                result = McpToolResult.failed("not authorized");
            } else {
                McpToolHandler handler = handlers.find(tool.toolName())
                        .orElseThrow(() -> new IllegalStateException("no handler registered for tool " + tool.toolName()));
                Object output = handler.handle(arguments == null ? Map.of() : arguments, context);
                result = McpToolResult.ok(output);
            }
        } catch (Exception e) {
            log.warn("MCP tool '{}' execution failed: {}", tool.toolName(), e.toString());
            result = McpToolResult.failed(e.toString());
        }

        long latencyMs = System.currentTimeMillis() - start;
        metrics.recordToolExecution(tool.toolName(), result.success(), latencyMs);
        if (healthManager != null) {
            healthManager.recordHeartbeat(tool.serverName(),
                    result.success() ? McpHealthStatus.UP : McpHealthStatus.DOWN, latencyMs);
        }
        try {
            auditor.record(tool, context, result);
        } catch (Exception e) {
            log.warn("MCP audit record failed for tool '{}': {}", tool.toolName(), e.toString());
        }
        return result;
    }
}
