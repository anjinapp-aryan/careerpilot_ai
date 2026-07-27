package ai.careerpilot.mcp.security;

import ai.careerpilot.mcp.McpCapability;
import ai.careerpilot.mcp.McpExecutionContext;
import ai.careerpilot.mcp.McpToolDefinition;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultMcpAuthorizationProviderTest {

    private final DefaultMcpAuthorizationProvider provider = new DefaultMcpAuthorizationProvider();
    private final McpToolDefinition tool = new McpToolDefinition("t", "d", Map.of(), Map.of(), McpCapability.DATABASE, "s");

    @Test
    void authorizesWhenContextHasAUserId() {
        McpExecutionContext context = new McpExecutionContext(UUID.randomUUID(), null, null, "trace", Duration.ofSeconds(1), Map.of());
        assertThat(provider.authorize(tool, context)).isTrue();
    }

    @Test
    void deniesWhenUserIdIsNull() {
        McpExecutionContext context = new McpExecutionContext(null, null, null, "trace", Duration.ofSeconds(1), Map.of());
        assertThat(provider.authorize(tool, context)).isFalse();
    }

    @Test
    void deniesWhenContextIsNull() {
        assertThat(provider.authorize(tool, null)).isFalse();
    }
}
