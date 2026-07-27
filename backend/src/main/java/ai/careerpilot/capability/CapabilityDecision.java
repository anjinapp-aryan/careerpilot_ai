package ai.careerpilot.capability;

import ai.careerpilot.mcp.McpToolDefinition;

import java.util.List;

/**
 * Phase 10.3 — the {@link CapabilityEngine}'s verdict for one request: whether tool calling
 * should happen and, if so, which tools. {@code capabilityType} is {@code null} when no
 * capability keyword matched (equivalent to "general chat," CLAUDE.md's existing default path)
 * — {@link CapabilityAwareChatService} treats a {@code null} type or {@code useToolCalling=false}
 * identically: fall back to the existing, untouched {@code AiGatewayService}.
 *
 * @param capabilityType the matched capability, or {@code null} if none matched
 * @param useToolCalling whether the caller should route through MCP tool calling
 * @param tools          the tools to execute, empty when {@code useToolCalling} is {@code false}
 * @param reason         human-readable explanation, always populated — used for logging/metrics,
 *                        e.g. "no capability keyword matched" or "tool.selection.enabled=false"
 */
public record CapabilityDecision(CapabilityType capabilityType, boolean useToolCalling,
                                  List<McpToolDefinition> tools, String reason) {

    public static CapabilityDecision noToolNeeded(String reason) {
        return new CapabilityDecision(null, false, List.of(), reason);
    }

    public static CapabilityDecision noToolNeeded(CapabilityType type, String reason) {
        return new CapabilityDecision(type, false, List.of(), reason);
    }

    public static CapabilityDecision useTools(CapabilityType type, List<McpToolDefinition> tools, String reason) {
        return new CapabilityDecision(type, true, tools, reason);
    }
}
