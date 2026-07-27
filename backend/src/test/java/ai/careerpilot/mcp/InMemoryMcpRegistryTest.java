package ai.careerpilot.mcp;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link InMemoryMcpRegistry} is the default {@link McpRegistry} implementation — this checks
 * server/tool registration, lookup, and capability-scoped queries behave as documented, and that
 * registration never does anything beyond in-memory bookkeeping (no exception, no I/O).
 */
class InMemoryMcpRegistryTest {

    private final InMemoryMcpRegistry registry = new InMemoryMcpRegistry();

    private McpServerDefinition server(String name) {
        return new McpServerDefinition(name, "1.0.0", Set.of(McpCapability.FILESYSTEM), true, 1, McpAuthenticationMode.NONE);
    }

    private McpToolDefinition tool(String toolName, String serverName, McpCapability capability) {
        return new McpToolDefinition(toolName, "desc", Map.of(), Map.of(), capability, serverName);
    }

    @Test
    void registersAndFindsServerByName() {
        registry.registerServer(server("filesystem"));

        assertThat(registry.findServer("filesystem")).isPresent();
        assertThat(registry.findServer("filesystem").get().name()).isEqualTo("filesystem");
    }

    @Test
    void findServerReturnsEmptyForUnknownName() {
        assertThat(registry.findServer("does-not-exist")).isEmpty();
    }

    @Test
    void registersAndFindsToolByName() {
        registry.registerTool(tool("read_file", "filesystem", McpCapability.FILESYSTEM));

        assertThat(registry.findTool("read_file")).isPresent();
        assertThat(registry.findTool("read_file").get().serverName()).isEqualTo("filesystem");
    }

    @Test
    void allServersAndAllToolsReflectEverythingRegistered() {
        registry.registerServer(server("filesystem"));
        registry.registerServer(server("postgres"));
        registry.registerTool(tool("read_file", "filesystem", McpCapability.FILESYSTEM));
        registry.registerTool(tool("query", "postgres", McpCapability.DATABASE));

        assertThat(registry.allServers()).hasSize(2);
        assertThat(registry.allTools()).hasSize(2);
    }

    @Test
    void toolsByCapabilityFiltersToOnlyThatCapability() {
        registry.registerTool(tool("read_file", "filesystem", McpCapability.FILESYSTEM));
        registry.registerTool(tool("write_file", "filesystem", McpCapability.FILESYSTEM));
        registry.registerTool(tool("query", "postgres", McpCapability.DATABASE));

        assertThat(registry.toolsByCapability(McpCapability.FILESYSTEM))
                .extracting(McpToolDefinition::toolName)
                .containsExactlyInAnyOrder("read_file", "write_file");
        assertThat(registry.toolsByCapability(McpCapability.DATABASE))
                .extracting(McpToolDefinition::toolName)
                .containsExactly("query");
        assertThat(registry.toolsByCapability(McpCapability.GITHUB)).isEmpty();
    }

    @Test
    void reRegisteringSameNameOverwritesRatherThanDuplicates() {
        registry.registerServer(server("filesystem"));
        registry.registerServer(new McpServerDefinition("filesystem", "2.0.0", Set.of(McpCapability.FILESYSTEM), true, 1, McpAuthenticationMode.API_KEY));

        assertThat(registry.allServers()).hasSize(1);
        assertThat(registry.findServer("filesystem").get().version()).isEqualTo("2.0.0");
    }
}
