package ai.careerpilot.mcp;

/**
 * Phase 10.1 — the only {@link McpMetrics} implementation shipped in this phase. Every method
 * is a deliberate no-op: this satisfies "prepare metrics, do not record anything yet" from the
 * phase spec without adding any Micrometer dependency or touching a real metrics registry.
 * Constructed only when {@code mcp.enabled=true} (see {@link McpConfig}).
 */
public class NoopMcpMetrics implements McpMetrics {

    @Override public void recordServerRegistered(String serverName) { /* intentionally no-op */ }

    @Override public void recordToolRegistered(String toolName) { /* intentionally no-op */ }

    @Override public void recordToolExecution(String toolName, boolean success, long latencyMs) { /* intentionally no-op */ }

    @Override public long registeredServerCount() { return 0; }

    @Override public long registeredToolCount() { return 0; }
}
