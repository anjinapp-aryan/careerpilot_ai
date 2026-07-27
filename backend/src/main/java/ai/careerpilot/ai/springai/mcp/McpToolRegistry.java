package ai.careerpilot.ai.springai.mcp;

import java.util.List;
import java.util.Optional;

/**
 * Phase 9.1 — extension-point only. The future contract for registering/looking up
 * {@link McpToolDefinition}s. <b>No implementation exists in this codebase</b> — no
 * bean implements this interface, nothing connects to an MCP server (filesystem,
 * PostgreSQL, GitHub, or otherwise). Wiring a real implementation, and the tool
 * servers themselves, is Phase 9.3+ scope.
 */
public interface McpToolRegistry {

    void register(McpToolDefinition tool);

    Optional<McpToolDefinition> find(String name);

    List<McpToolDefinition> all();
}
