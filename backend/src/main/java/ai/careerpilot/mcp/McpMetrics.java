package ai.careerpilot.mcp;

/**
 * Phase 10.1 — observability contract for the MCP platform: registered server/tool counts,
 * per-call execution count, latency, and failures. Per the phase spec ("Prepare metrics. Do
 * NOT record anything yet"), {@link NoopMcpMetrics} is the only implementation shipped in this
 * phase — it satisfies the contract without touching Micrometer or any real metrics registry.
 * A future phase wires a real implementation (e.g. backed by {@code
 * ai.careerpilot.ai.AiMetrics}'s existing Micrometer conventions) without this interface, or any
 * caller of it, needing to change.
 */
public interface McpMetrics {

    void recordServerRegistered(String serverName);

    void recordToolRegistered(String toolName);

    void recordToolExecution(String toolName, boolean success, long latencyMs);

    long registeredServerCount();

    long registeredToolCount();
}
