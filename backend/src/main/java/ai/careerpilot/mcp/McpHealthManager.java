package ai.careerpilot.mcp;

import java.util.List;

/**
 * Phase 10.1 — tracks and reports {@link McpServerHealth} for every server registered in an
 * {@link McpRegistry}. This interface is the health contract; {@link #recordHeartbeat} is
 * intentionally never called by anything in this phase (no server is connected to, so there is
 * nothing to heartbeat) — see {@code mcp.health.enabled}, which gates whether a future
 * scheduler would even be allowed to call it.
 */
public interface McpHealthManager {

    McpServerHealth healthOf(String serverName);

    List<McpServerHealth> allHealth();

    void recordHeartbeat(String serverName, McpHealthStatus status, long latencyMs);
}
