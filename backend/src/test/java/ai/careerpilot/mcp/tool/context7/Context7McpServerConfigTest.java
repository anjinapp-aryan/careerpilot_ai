package ai.careerpilot.mcp.tool.context7;

import ai.careerpilot.mcp.InMemoryMcpMetrics;
import ai.careerpilot.mcp.InMemoryMcpRegistry;
import ai.careerpilot.mcp.McpProperties;
import ai.careerpilot.mcp.McpServerDefinition;
import ai.careerpilot.mcp.tool.McpToolHandlerRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link Context7McpServerConfig} — verifies the keyed-provider "joins the chain only if
 * configured" behavior: the server is always registered (visible for diagnostics) but its tool
 * is only registered when an api-key is actually present, matching {@code
 * Context7ApiClient#isConfigured()}.
 */
class Context7McpServerConfigTest {

    private final InMemoryMcpRegistry registry = new InMemoryMcpRegistry();
    private final McpToolHandlerRegistry handlers = new McpToolHandlerRegistry();
    private final InMemoryMcpMetrics metrics = new InMemoryMcpMetrics();
    private final Context7McpServerConfig config = new Context7McpServerConfig();

    @Test
    void withNoApiKey_serverRegisteredAsDisabled_toolNotRegistered() {
        McpProperties properties = new McpProperties();
        properties.getContext7().setApiKey("");

        McpServerDefinition server = config.context7McpServer(registry, handlers, metrics, properties);

        assertThat(server.enabled()).isFalse();
        assertThat(registry.findServer("context7")).isPresent();
        assertThat(registry.findTool("search_documentation")).isEmpty();
        assertThat(handlers.find("search_documentation")).isEmpty();
    }

    @Test
    void withApiKeyConfigured_serverEnabledAndToolRegistered() {
        McpProperties properties = new McpProperties();
        properties.getContext7().setApiKey("test-key-123");

        McpServerDefinition server = config.context7McpServer(registry, handlers, metrics, properties);

        assertThat(server.enabled()).isTrue();
        assertThat(registry.findTool("search_documentation")).isPresent();
        assertThat(handlers.find("search_documentation")).isPresent();
    }

    @Test
    void handlerDegradesGracefullyWhenQueryArgumentMissing() {
        McpProperties properties = new McpProperties();
        properties.getContext7().setApiKey("test-key-123");
        config.context7McpServer(registry, handlers, metrics, properties);

        var handler = handlers.find("search_documentation").orElseThrow();
        @SuppressWarnings("unchecked")
        var result = (java.util.Map<String, Object>) handler.handle(java.util.Map.of(), null);

        assertThat(result.get("available")).isEqualTo(false);
    }
}
