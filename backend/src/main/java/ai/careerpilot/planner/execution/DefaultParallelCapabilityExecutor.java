package ai.careerpilot.planner.execution;

import ai.careerpilot.capability.CapabilityType;
import ai.careerpilot.mcp.McpExecutionContext;
import ai.careerpilot.planner.CapabilityStep;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Phase 11.3 — the default {@link ParallelCapabilityExecutor}: one {@link CompletableFuture} per
 * step in the stage, joined together, mirroring the exact parallel-execution shape already
 * established by {@code ai.careerpilot.capability.CapabilityAwareChatService.executeTools}
 * (Phase 10.3) — but one level up, over whole capabilities instead of individual MCP tools.
 * {@link CapabilityExecutor#execute} itself already never throws (see its javadoc), so no
 * additional exception handling is needed here — a failing step still resolves its future
 * normally with a failed {@link ExecutionResult}.
 */
public class DefaultParallelCapabilityExecutor implements ParallelCapabilityExecutor {

    private final CapabilityExecutor executor;
    private final MultiCapabilityMetrics metrics;

    public DefaultParallelCapabilityExecutor(CapabilityExecutor executor, MultiCapabilityMetrics metrics) {
        this.executor = executor;
        this.metrics = metrics;
    }

    @Override
    public Map<CapabilityType, ExecutionResult> executeStage(List<CapabilityStep> stage, McpExecutionContext context) {
        metrics.recordStageSize(stage.size());

        Map<CapabilityType, CompletableFuture<ExecutionResult>> futures = new LinkedHashMap<>();
        for (CapabilityStep step : stage) {
            futures.put(step.type(), CompletableFuture.supplyAsync(() -> executor.execute(step, context)));
        }
        CompletableFuture.allOf(futures.values().toArray(new CompletableFuture[0])).join();

        Map<CapabilityType, ExecutionResult> results = new LinkedHashMap<>();
        futures.forEach((type, future) -> results.put(type, future.join()));
        return results;
    }
}
