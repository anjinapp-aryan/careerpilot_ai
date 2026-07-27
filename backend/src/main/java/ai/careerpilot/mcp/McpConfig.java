package ai.careerpilot.mcp;

import ai.careerpilot.mcp.security.DefaultMcpAuthenticationProvider;
import ai.careerpilot.mcp.security.DefaultMcpAuthorizationProvider;
import ai.careerpilot.mcp.security.LoggingMcpAuditor;
import ai.careerpilot.mcp.security.McpAuditor;
import ai.careerpilot.mcp.security.McpAuthenticationProvider;
import ai.careerpilot.mcp.security.McpAuthorizationProvider;
import ai.careerpilot.mcp.springai.DefaultSpringAiMcpBridge;
import ai.careerpilot.mcp.springai.DefaultToolCallingAdapter;
import ai.careerpilot.mcp.springai.SpringAiMcpBridge;
import ai.careerpilot.mcp.springai.ToolCallingAdapter;
import ai.careerpilot.mcp.tool.DefaultMcpExecutor;
import ai.careerpilot.mcp.tool.McpToolHandlerRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Phase 10.1/10.2 — constructs the MCP platform's core beans: the registry, execution plumbing
 * (handler registry, executor, security defaults), Spring AI bridge, and health/metrics. All
 * gated on {@code mcp.enabled} (default {@code false} — with the flag off, none of these beans
 * exist, so nothing in the application context can depend on any of them by accident). {@link
 * McpHealthManager} keeps its own narrower gate, {@code mcp.health.enabled}, independent of
 * {@code mcp.enabled} (matching CLAUDE.md's "two independent flags per stage" convention).
 *
 * <p>Individual MCP servers (filesystem/postgres/github/memory/context7 — Phase 10.2) are
 * deliberately NOT constructed here: each owns its own {@code @Configuration} class under {@code
 * ai.careerpilot.mcp.tool.<server>}, gated by both {@code mcp.enabled} and its own {@code
 * mcp.<server>.enabled} flag, and depends on the beans this class provides. This class remains
 * the source of the platform's core beans, not every MCP bean in the codebase — the "every
 * future MCP server should require only registration" plugin principle means new servers plug
 * into this class's beans rather than being added to this class itself.
 *
 * <p>No business service, controller, or {@code AiGatewayService}/Smart Router code references
 * this class or any type it constructs — see the package javadoc for the full "zero production
 * behavior change" guarantee.
 */
@Configuration
@EnableConfigurationProperties(McpProperties.class)
public class McpConfig {

    @Bean
    @ConditionalOnProperty(prefix = "mcp", name = "enabled", havingValue = "true")
    public McpRegistry mcpRegistry() {
        return new InMemoryMcpRegistry();
    }

    @Bean
    @ConditionalOnProperty(prefix = "mcp", name = "enabled", havingValue = "true")
    public McpMetrics mcpMetrics() {
        return new InMemoryMcpMetrics();
    }

    @Bean
    @ConditionalOnProperty(prefix = "mcp.health", name = "enabled", havingValue = "true")
    public McpHealthManager mcpHealthManager() {
        return new InMemoryMcpHealthManager();
    }

    @Bean
    @ConditionalOnProperty(prefix = "mcp", name = "enabled", havingValue = "true")
    public McpToolHandlerRegistry mcpToolHandlerRegistry() {
        return new McpToolHandlerRegistry();
    }

    @Bean
    @ConditionalOnProperty(prefix = "mcp", name = "enabled", havingValue = "true")
    public McpAuthorizationProvider mcpAuthorizationProvider() {
        return new DefaultMcpAuthorizationProvider();
    }

    @Bean
    @ConditionalOnProperty(prefix = "mcp", name = "enabled", havingValue = "true")
    public McpAuthenticationProvider mcpAuthenticationProvider() {
        return new DefaultMcpAuthenticationProvider();
    }

    @Bean
    @ConditionalOnProperty(prefix = "mcp", name = "enabled", havingValue = "true")
    public McpAuditor mcpAuditor() {
        return new LoggingMcpAuditor();
    }

    @Bean
    @ConditionalOnProperty(prefix = "mcp", name = "enabled", havingValue = "true")
    public McpExecutor mcpExecutor(McpToolHandlerRegistry handlers,
                                    McpAuthorizationProvider authorization,
                                    McpAuditor auditor,
                                    McpMetrics metrics,
                                    ObjectProvider<McpHealthManager> healthManagerProvider) {
        return new DefaultMcpExecutor(handlers, authorization, auditor, metrics, healthManagerProvider);
    }

    @Bean
    @ConditionalOnProperty(prefix = "mcp", name = "enabled", havingValue = "true")
    public ToolCallingAdapter mcpToolCallingAdapter(McpExecutor executor) {
        return new DefaultToolCallingAdapter(executor);
    }

    @Bean
    @ConditionalOnProperty(prefix = "mcp", name = "enabled", havingValue = "true")
    public SpringAiMcpBridge mcpSpringAiBridge(McpRegistry registry, ToolCallingAdapter adapter) {
        return new DefaultSpringAiMcpBridge(registry, adapter);
    }
}
