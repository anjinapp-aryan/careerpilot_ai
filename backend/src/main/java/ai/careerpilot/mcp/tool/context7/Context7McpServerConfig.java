package ai.careerpilot.mcp.tool.context7;

import ai.careerpilot.mcp.McpAuthenticationMode;
import ai.careerpilot.mcp.McpCapability;
import ai.careerpilot.mcp.McpMetrics;
import ai.careerpilot.mcp.McpProperties;
import ai.careerpilot.mcp.McpRegistry;
import ai.careerpilot.mcp.McpServerDefinition;
import ai.careerpilot.mcp.McpToolDefinition;
import ai.careerpilot.mcp.tool.McpToolHandlerRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.Set;

/**
 * Phase 10.2 — Context7 MCP server. Unlike the other four Phase 10.2 servers, this one is
 * additionally keyed (see {@link Context7ApiClient}'s javadoc for the "not live-verified"
 * caveat) — it registers itself (server visible, enabled=false in its own definition) even
 * without an api-key so it's observable via diagnostics, but does NOT register its tool (so it
 * can never be invoked) unless {@link Context7ApiClient#isConfigured()} is true, matching the
 * "a provider only joins the chain when isConfigured() is true" convention documented in
 * CLAUDE.md for {@code LlmProvider}. Gated by BOTH {@code mcp.enabled} and {@code
 * mcp.context7.enabled} (both default {@code false}).
 */
@Configuration
@ConditionalOnProperty(prefix = "mcp", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "mcp.context7", name = "enabled", havingValue = "true")
public class Context7McpServerConfig {

    private static final Logger log = LoggerFactory.getLogger(Context7McpServerConfig.class);
    private static final String SERVER = "context7";
    private static final String TOOL = "search_documentation";

    @Bean
    public McpServerDefinition context7McpServer(McpRegistry registry,
                                                  McpToolHandlerRegistry handlers,
                                                  McpMetrics metrics,
                                                  McpProperties properties) {
        Context7ApiClient client = new Context7ApiClient(
                properties.getContext7().getBaseUrl(),
                properties.getContext7().getApiKey(),
                properties.getContext7().getTimeoutMs());

        boolean configured = client.isConfigured();
        McpServerDefinition server = new McpServerDefinition(
                SERVER, "1.0.0", Set.of(McpCapability.KNOWLEDGE), configured, 1, McpAuthenticationMode.API_KEY);
        registry.registerServer(server);
        metrics.recordServerRegistered(SERVER);

        if (!configured) {
            log.info("MCP Context7 server registered but not configured (mcp.context7.api-key is blank) — tool not registered");
            return server;
        }

        McpToolDefinition tool = new McpToolDefinition(
                TOOL,
                "Searches Context7 for framework/API documentation (Spring Boot, Spring AI, Java, AWS, Terraform, Docker, Kubernetes, React, LangGraph).",
                Map.of("type", "object", "properties", Map.of(
                        "query", Map.of("type", "string", "description", "documentation search query"))),
                Map.of("type", "object"),
                McpCapability.KNOWLEDGE,
                SERVER);
        registry.registerTool(tool);
        metrics.recordToolRegistered(TOOL);

        handlers.register(TOOL, (args, context) -> handle(args, client));
        return server;
    }

    private Object handle(Map<String, Object> args, Context7ApiClient client) {
        Object queryArg = args.get("query");
        if (!(queryArg instanceof String query) || query.isBlank()) {
            return Map.of("available", false, "reason", "missing required argument 'query'");
        }
        JsonNode result = client.search(query);
        return Map.of("available", true, "query", query, "result", result == null ? Map.of() : result);
    }
}
