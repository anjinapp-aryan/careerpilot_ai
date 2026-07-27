package ai.careerpilot.planner.execution;

import ai.careerpilot.capability.CapabilityDefinition;
import ai.careerpilot.capability.CapabilityRegistry;
import ai.careerpilot.capability.ToolSelectionEngine;
import ai.careerpilot.mcp.McpExecutionContext;
import ai.careerpilot.mcp.McpExecutor;
import ai.careerpilot.mcp.McpToolDefinition;
import ai.careerpilot.mcp.McpToolResult;
import ai.careerpilot.planner.CapabilityStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Phase 11.3 — the default {@link CapabilityExecutor}. Resolves a step's tools the identical way
 * {@code ai.careerpilot.capability.DefaultToolSelectionEngine} already does (via the live {@code
 * CapabilityRegistry}/{@code ToolSelectionEngine} from Phase 10.3 — no duplicated lookup logic),
 * then runs each through the Phase 10.2 {@link McpExecutor} — never a tool handler directly, per
 * the platform's own security contract.
 *
 * <p><b>Retries</b>: a step is retried (whole step, not per-tool) up to {@code maxRetries}
 * additional times if it didn't fully succeed, keeping only the final attempt's results — this
 * favors simplicity over partial-tool-level retry bookkeeping. <b>Partial failures</b>: any
 * exception from a single tool call is caught and converted to {@code McpToolResult.failed(...)}
 * for that tool only; the remaining tools in the same step still run (mirrors {@code
 * CapabilityAwareChatService.executeTools}'s existing per-tool isolation).
 */
public class DefaultCapabilityExecutor implements CapabilityExecutor {

    private static final Logger log = LoggerFactory.getLogger(DefaultCapabilityExecutor.class);

    private final ObjectProvider<CapabilityRegistry> registryProvider;
    private final ObjectProvider<ToolSelectionEngine> toolSelectionProvider;
    private final ObjectProvider<McpExecutor> mcpExecutorProvider;
    private final MultiCapabilityMetrics metrics;
    private final int maxRetries;

    public DefaultCapabilityExecutor(ObjectProvider<CapabilityRegistry> registryProvider,
                                      ObjectProvider<ToolSelectionEngine> toolSelectionProvider,
                                      ObjectProvider<McpExecutor> mcpExecutorProvider,
                                      MultiCapabilityMetrics metrics,
                                      int maxRetries) {
        this.registryProvider = registryProvider;
        this.toolSelectionProvider = toolSelectionProvider;
        this.mcpExecutorProvider = mcpExecutorProvider;
        this.metrics = metrics;
        this.maxRetries = Math.max(0, maxRetries);
    }

    @Override
    public ExecutionResult execute(CapabilityStep step, McpExecutionContext context) {
        long start = System.currentTimeMillis();
        int attempt = 1;
        ExecutionResult result = attempt(step, context);
        while (!result.success() && attempt <= maxRetries) {
            metrics.recordRetry(step.type().name(), attempt);
            attempt++;
            result = attempt(step, context);
        }
        long latencyMs = System.currentTimeMillis() - start;
        metrics.recordCapabilityExecutionTime(step.type().name(), latencyMs);
        if (!result.success()) {
            metrics.recordPartialFailure(step.type().name());
        }
        return new ExecutionResult(step.type(), result.toolResults(), result.success(), attempt, latencyMs, result.error());
    }

    private ExecutionResult attempt(CapabilityStep step, McpExecutionContext context) {
        CapabilityRegistry registry = registryProvider.getIfAvailable();
        ToolSelectionEngine toolSelection = toolSelectionProvider.getIfAvailable();
        McpExecutor executor = mcpExecutorProvider.getIfAvailable();
        if (registry == null || toolSelection == null || executor == null) {
            return new ExecutionResult(step.type(), Map.of(), false, 1, 0,
                    "capability platform unavailable (mcp.enabled/capability.engine.enabled off)");
        }

        Optional<CapabilityDefinition> definition = registry.find(step.type());
        if (definition.isEmpty()) {
            return new ExecutionResult(step.type(), Map.of(), false, 1, 0, "capability not registered: " + step.type());
        }

        List<McpToolDefinition> tools = toolSelection.selectTools(definition.get());
        if (tools.isEmpty()) {
            return new ExecutionResult(step.type(), Map.of(), false, 1, 0, "no MCP tools registered for " + step.type());
        }

        Map<String, McpToolResult> toolResults = new LinkedHashMap<>();
        boolean allSucceeded = true;
        for (McpToolDefinition tool : tools) {
            McpToolResult result;
            try {
                result = executor.execute(tool, Map.of(), context).block();
                if (result == null) {
                    result = McpToolResult.failed("tool returned no result");
                }
            } catch (Exception e) {
                log.warn("Capability step '{}' tool '{}' failed: {}", step.type(), tool.toolName(), e.toString());
                result = McpToolResult.failed(e.toString());
            }
            toolResults.put(tool.toolName(), result);
            if (!result.success()) {
                allSucceeded = false;
            }
        }

        String error = allSucceeded ? null : "one or more tools failed for " + step.type();
        return new ExecutionResult(step.type(), toolResults, allSucceeded, 1, 0, error);
    }
}
