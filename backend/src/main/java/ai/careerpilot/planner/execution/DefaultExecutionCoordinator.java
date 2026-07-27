package ai.careerpilot.planner.execution;

import ai.careerpilot.capability.CapabilityType;
import ai.careerpilot.mcp.McpExecutionContext;
import ai.careerpilot.planner.CapabilityPlan;
import ai.careerpilot.planner.CapabilityStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Phase 11.3 — the default {@link ExecutionCoordinator}. Walks an {@link ExecutionGraph} (built
 * from the plan's already-dependency-resolved stages — see {@code
 * ai.careerpilot.planner.PlanOptimizer}, Phase 11.2) stage by stage, sequentially between stages
 * (a later stage may depend on an earlier one's results) but delegating each stage to {@link
 * ParallelCapabilityExecutor} (parallel within the stage, since {@link
 * ai.careerpilot.planner.PlanOptimizer} already guarantees no intra-stage dependency).
 *
 * <p><b>Partial failures never stop the plan</b>: a failed capability in stage N is recorded in
 * the final result and execution proceeds to stage N+1 regardless — a dependent step in a later
 * stage simply won't find its dependency's data in the merged context (visible to a future
 * caller via {@link ExecutionResult#success()}), rather than the whole plan aborting. This
 * matches the "partial failures, continue with remaining" discipline established at the MCP
 * tool level in Phase 10.4 and applied here one layer up, at the capability level.
 */
public class DefaultExecutionCoordinator implements ExecutionCoordinator {

    private static final Logger log = LoggerFactory.getLogger(DefaultExecutionCoordinator.class);

    private final ParallelCapabilityExecutor stageExecutor;
    private final ResultMerger merger;
    private final MultiCapabilityMetrics metrics;

    public DefaultExecutionCoordinator(ParallelCapabilityExecutor stageExecutor, ResultMerger merger, MultiCapabilityMetrics metrics) {
        this.stageExecutor = stageExecutor;
        this.merger = merger;
        this.metrics = metrics;
    }

    @Override
    public MultiCapabilityResult execute(CapabilityPlan plan, McpExecutionContext context) {
        if (plan == null || plan.isEmpty()) {
            return MultiCapabilityResult.empty(plan == null ? "no plan" : plan.reason());
        }

        long start = System.currentTimeMillis();
        Map<CapabilityType, ExecutionResult> allResults = new LinkedHashMap<>();

        try {
            ExecutionGraph graph = ExecutionGraph.from(plan);
            for (java.util.List<CapabilityStep> stage : graph.stages()) {
                Map<CapabilityType, ExecutionResult> stageResults = stageExecutor.executeStage(stage, context);
                allResults.putAll(stageResults);
            }
        } catch (Exception e) {
            log.warn("Multi-capability execution failed for intent {}, returning partial results: {}", plan.intentType(), e.toString());
        }

        long latencyMs = System.currentTimeMillis() - start;
        metrics.recordPlanExecutionLatency(latencyMs);

        boolean allSucceeded = allResults.values().stream().allMatch(ExecutionResult::success);
        MergedExecutionContext merged = merger.merge(allResults);

        return new MultiCapabilityResult(plan.intentType(), Map.copyOf(allResults), merged, allSucceeded, latencyMs,
                "executed plan for intent " + plan.intentType());
    }
}
