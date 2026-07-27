package ai.careerpilot.capability;

import ai.careerpilot.mcp.McpRegistry;
import ai.careerpilot.mcp.McpToolDefinition;
import org.springframework.beans.factory.ObjectProvider;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 10.3 — the default {@link ToolSelectionEngine}. For each {@link
 * ai.careerpilot.mcp.McpCapability} a {@link CapabilityDefinition} declares, asks the live {@code
 * McpRegistry} (Phase 10.1/10.2) for every registered tool under that capability — never a
 * hardcoded tool name, so a new MCP server registered under an existing capability is picked up
 * automatically. {@code McpRegistry} only exists when {@code mcp.enabled=true} (independent of
 * {@code capability.engine.enabled}); when absent, this always returns an empty list — a
 * capability match with no MCP platform available degrades to "no tools," never an error.
 */
public class DefaultToolSelectionEngine implements ToolSelectionEngine {

    private final ObjectProvider<McpRegistry> registryProvider;

    public DefaultToolSelectionEngine(ObjectProvider<McpRegistry> registryProvider) {
        this.registryProvider = registryProvider;
    }

    @Override
    public List<McpToolDefinition> selectTools(CapabilityDefinition definition) {
        McpRegistry registry = registryProvider.getIfAvailable();
        if (registry == null) {
            return List.of();
        }
        // LinkedHashMap keyed by tool name: dedupes a tool that might satisfy more than one
        // required McpCapability while preserving first-seen (capability-declaration) order.
        Map<String, McpToolDefinition> byName = new LinkedHashMap<>();
        for (var capability : definition.requiredMcpCapabilities()) {
            for (McpToolDefinition tool : registry.toolsByCapability(capability)) {
                byName.putIfAbsent(tool.toolName(), tool);
            }
        }
        return List.copyOf(byName.values());
    }
}
