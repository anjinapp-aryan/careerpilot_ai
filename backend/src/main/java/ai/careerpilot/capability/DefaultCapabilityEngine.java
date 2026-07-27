package ai.careerpilot.capability;

import ai.careerpilot.mcp.McpToolDefinition;

import java.util.List;
import java.util.Optional;

/**
 * Phase 10.3 — the default {@link CapabilityEngine}. Decision chain: resolve a {@link
 * CapabilityType} from the message → look up its {@link CapabilityDefinition} → (if {@code
 * tool.selection.enabled}) resolve concrete tools via {@link ToolSelectionEngine} → require at
 * least one real tool before committing to tool calling. Any break in that chain (no keyword
 * match, no definition, flag off, zero tools resolved) falls back to {@code
 * CapabilityDecision.noToolNeeded(...)} with a specific reason — {@link
 * CapabilityAwareChatService} treats every "no tool needed" verdict identically: call the
 * existing, untouched {@code AiGatewayService}.
 */
public class DefaultCapabilityEngine implements CapabilityEngine {

    private final CapabilityRegistry registry;
    private final CapabilityResolver resolver;
    private final ToolSelectionEngine toolSelectionEngine;
    private final CapabilityMetrics metrics;
    private final boolean toolSelectionEnabled;

    public DefaultCapabilityEngine(CapabilityRegistry registry,
                                    CapabilityResolver resolver,
                                    ToolSelectionEngine toolSelectionEngine,
                                    CapabilityMetrics metrics,
                                    boolean toolSelectionEnabled) {
        this.registry = registry;
        this.resolver = resolver;
        this.toolSelectionEngine = toolSelectionEngine;
        this.metrics = metrics;
        this.toolSelectionEnabled = toolSelectionEnabled;
    }

    @Override
    public CapabilityDecision analyze(String message) {
        long start = System.currentTimeMillis();
        CapabilityDecision decision = doAnalyze(message);
        metrics.recordCapabilityLatency(decision.capabilityType() == null ? "none" : decision.capabilityType().name(),
                System.currentTimeMillis() - start);
        return decision;
    }

    private CapabilityDecision doAnalyze(String message) {
        CapabilityType type = resolver.resolve(message);
        if (type == null) {
            return CapabilityDecision.noToolNeeded("no capability keyword matched");
        }
        if (!toolSelectionEnabled) {
            return CapabilityDecision.noToolNeeded(type, "tool.selection.enabled=false");
        }
        Optional<CapabilityDefinition> definition = registry.find(type);
        if (definition.isEmpty()) {
            return CapabilityDecision.noToolNeeded(type, "capability not registered: " + type);
        }
        List<McpToolDefinition> tools = toolSelectionEngine.selectTools(definition.get());
        if (tools.isEmpty()) {
            return CapabilityDecision.noToolNeeded(type, "no MCP tools registered for " + type + "'s required capabilities");
        }
        metrics.recordCapabilitySelected(type.name());
        return CapabilityDecision.useTools(type, tools, "matched capability " + type);
    }
}
