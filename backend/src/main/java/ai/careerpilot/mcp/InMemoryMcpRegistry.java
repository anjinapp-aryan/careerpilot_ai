package ai.careerpilot.mcp;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 10.1 — the default {@link McpRegistry} implementation: a plain in-process, thread-safe
 * map. Only constructed when {@code mcp.enabled=true} (see {@link McpConfig}) — with the flag
 * at its default {@code false}, this bean does not exist and nothing in the application holds a
 * reference to an {@link McpRegistry} at all. Registering a server/tool here does not connect
 * to anything; it is bookkeeping only, matching this interface's own contract.
 */
public class InMemoryMcpRegistry implements McpRegistry {

    private final Map<String, McpServerDefinition> servers = new ConcurrentHashMap<>();
    private final Map<String, McpToolDefinition> tools = new ConcurrentHashMap<>();

    @Override
    public void registerServer(McpServerDefinition server) {
        servers.put(server.name(), server);
    }

    @Override
    public void registerTool(McpToolDefinition tool) {
        tools.put(tool.toolName(), tool);
    }

    @Override
    public Optional<McpServerDefinition> findServer(String name) {
        return Optional.ofNullable(servers.get(name));
    }

    @Override
    public Optional<McpToolDefinition> findTool(String toolName) {
        return Optional.ofNullable(tools.get(toolName));
    }

    @Override
    public List<McpServerDefinition> allServers() {
        return List.copyOf(servers.values());
    }

    @Override
    public List<McpToolDefinition> allTools() {
        return List.copyOf(tools.values());
    }

    @Override
    public List<McpToolDefinition> toolsByCapability(McpCapability capability) {
        return tools.values().stream()
                .filter(t -> t.capability() == capability)
                .toList();
    }
}
