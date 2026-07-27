package ai.careerpilot.mcp.tool;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 10.2 — maps a registered tool's name to the {@link McpToolHandler} that actually
 * executes it. Deliberately separate from {@link ai.careerpilot.mcp.McpRegistry} (which holds
 * pure metadata, no behavior) — this is where the one bit of real "execution" wiring lives,
 * consumed only by {@link DefaultMcpExecutor}. Each per-server config (e.g. {@code
 * FilesystemMcpServerConfig}) registers its own tool's handler here alongside registering the
 * tool's {@code McpToolDefinition} metadata in {@code McpRegistry} — the two registrations
 * happen together, in the same bean-construction step, so they can never drift apart.
 */
public class McpToolHandlerRegistry {

    private final Map<String, McpToolHandler> handlers = new ConcurrentHashMap<>();

    public void register(String toolName, McpToolHandler handler) {
        handlers.put(toolName, handler);
    }

    public Optional<McpToolHandler> find(String toolName) {
        return Optional.ofNullable(handlers.get(toolName));
    }
}
