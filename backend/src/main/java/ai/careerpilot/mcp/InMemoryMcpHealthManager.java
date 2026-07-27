package ai.careerpilot.mcp;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 10.1 — default {@link McpHealthManager} implementation: an in-process map from server
 * name to its last known {@link McpServerHealth}. Only constructed when {@code
 * mcp.health.enabled=true} (see {@link McpConfig}). Nothing calls {@link
 * #recordHeartbeat} in this phase — no server is connected to — so every lookup returns {@link
 * McpServerHealth#unknown} until a future phase wires a real heartbeat source.
 */
public class InMemoryMcpHealthManager implements McpHealthManager {

    private final Map<String, McpServerHealth> health = new ConcurrentHashMap<>();

    @Override
    public McpServerHealth healthOf(String serverName) {
        return health.getOrDefault(serverName, McpServerHealth.unknown(serverName));
    }

    @Override
    public List<McpServerHealth> allHealth() {
        return List.copyOf(health.values());
    }

    @Override
    public void recordHeartbeat(String serverName, McpHealthStatus status, long latencyMs) {
        health.put(serverName, new McpServerHealth(serverName, status, latencyMs, java.time.Instant.now()));
    }
}
