package ai.careerpilot.mcp;

import java.util.List;
import java.util.Optional;

/**
 * Phase 10.1 — the plug-and-play entry point every future MCP server registers through. A
 * server integration is expected to be nothing more than a {@code @Configuration} class whose
 * {@code @Bean} method calls {@link #registerServer} (and {@link #registerTool} for each tool
 * it exposes) — see the package javadoc's "Plugin Architecture" section. This interface itself
 * performs no I/O, spawns no process, and opens no connection; it is bookkeeping only. Server
 * lifecycle beyond registration (starting/stopping a real MCP connection) belongs to a future
 * {@link McpExecutor} implementation, not this registry.
 */
public interface McpRegistry {

    void registerServer(McpServerDefinition server);

    void registerTool(McpToolDefinition tool);

    Optional<McpServerDefinition> findServer(String name);

    Optional<McpToolDefinition> findTool(String toolName);

    List<McpServerDefinition> allServers();

    List<McpToolDefinition> allTools();

    List<McpToolDefinition> toolsByCapability(McpCapability capability);
}
