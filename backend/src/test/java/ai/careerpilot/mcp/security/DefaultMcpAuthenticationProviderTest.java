package ai.careerpilot.mcp.security;

import ai.careerpilot.mcp.McpAuthenticationMode;
import ai.careerpilot.mcp.McpCapability;
import ai.careerpilot.mcp.McpServerDefinition;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DefaultMcpAuthenticationProvider} — always returns {@code true}, per its own javadoc
 * (external-system auth is each client's own {@code isConfigured()} check, not this hook).
 */
class DefaultMcpAuthenticationProviderTest {

    private final DefaultMcpAuthenticationProvider provider = new DefaultMcpAuthenticationProvider();

    @Test
    void authenticatesRegardlessOfServerOrContext() {
        McpServerDefinition server = new McpServerDefinition("s", "1.0", Set.of(McpCapability.DATABASE), true, 1, McpAuthenticationMode.NONE);
        assertThat(provider.authenticate(server, null)).isTrue();
    }
}
