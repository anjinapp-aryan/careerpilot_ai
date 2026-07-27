package ai.careerpilot.mcp;

import java.time.Instant;

/**
 * Phase 10.1 — the health snapshot {@link McpHealthManager} holds for one registered {@link
 * McpServerDefinition}, keyed by {@link McpServerDefinition#name()}. Separate from the
 * definition itself because health changes continuously while the definition's metadata does
 * not — same separation of concerns as {@code ai.careerpilot.ai.ProviderHealthTracker} vs. the
 * static {@code LlmProvider} configuration it tracks.
 *
 * @param serverName    matches {@link McpServerDefinition#name()}
 * @param status        current {@link McpHealthStatus}
 * @param latencyMs     last observed round-trip latency, or {@code -1} if never measured
 * @param lastHeartbeat when this snapshot was last updated, or {@code null} if never checked
 */
public record McpServerHealth(String serverName, McpHealthStatus status, long latencyMs, Instant lastHeartbeat) {

    public static McpServerHealth unknown(String serverName) {
        return new McpServerHealth(serverName, McpHealthStatus.UNKNOWN, -1, null);
    }
}
