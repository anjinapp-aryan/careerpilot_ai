package ai.careerpilot.mcp;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 10.2 — the first real {@link McpMetrics} implementation, replacing {@link
 * NoopMcpMetrics} as {@link McpConfig}'s default now that Phase 10.2 wires actual MCP tool
 * servers (see {@code ai.careerpilot.mcp.tool}) worth measuring. Plain in-memory {@link
 * AtomicLong} counters per tool name — the same hand-rolled style as {@code
 * ai.careerpilot.ai.AiMetrics} (which, despite its name suggesting Micrometer, is itself a
 * {@code ConcurrentHashMap<String, AtomicLong>}-based component, not Micrometer-backed) rather
 * than a new dependency. {@link NoopMcpMetrics} remains in the codebase (still a valid, harmless
 * {@link McpMetrics} implementation) but is no longer constructed by {@link McpConfig}.
 */
public class InMemoryMcpMetrics implements McpMetrics {

    private final AtomicLong serverCount = new AtomicLong();
    private final AtomicLong toolCount = new AtomicLong();
    private final Map<String, AtomicLong> executions = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> successes = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> failures = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> latencySumMs = new ConcurrentHashMap<>();

    @Override
    public void recordServerRegistered(String serverName) {
        serverCount.incrementAndGet();
    }

    @Override
    public void recordToolRegistered(String toolName) {
        toolCount.incrementAndGet();
    }

    @Override
    public void recordToolExecution(String toolName, boolean success, long latencyMs) {
        executions.computeIfAbsent(toolName, k -> new AtomicLong()).incrementAndGet();
        (success ? successes : failures).computeIfAbsent(toolName, k -> new AtomicLong()).incrementAndGet();
        latencySumMs.computeIfAbsent(toolName, k -> new AtomicLong()).addAndGet(latencyMs);
    }

    @Override
    public long registeredServerCount() {
        return serverCount.get();
    }

    @Override
    public long registeredToolCount() {
        return toolCount.get();
    }

    public long executionCount(String toolName) {
        return executions.getOrDefault(toolName, new AtomicLong()).get();
    }

    public long successCount(String toolName) {
        return successes.getOrDefault(toolName, new AtomicLong()).get();
    }

    public long failureCount(String toolName) {
        return failures.getOrDefault(toolName, new AtomicLong()).get();
    }

    public long avgLatencyMs(String toolName) {
        long count = executionCount(toolName);
        return count == 0 ? 0 : latencySumMs.getOrDefault(toolName, new AtomicLong()).get() / count;
    }
}
