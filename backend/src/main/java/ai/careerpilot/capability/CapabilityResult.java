package ai.careerpilot.capability;

import ai.careerpilot.mcp.McpToolResult;

import java.util.Map;

/**
 * Phase 10.3 — the outcome of {@link CapabilityAwareChatService#chat}. Carries enough detail
 * for the "Verify that disabling feature flags restores the original behaviour exactly"
 * requirement to be checked by a caller: {@code usedToolCalling=false} means the answer came
 * from the plain, untouched {@code AiGatewayService.chat(...)} call — byte-for-byte the same
 * code path as before this phase existed.
 *
 * @param capabilityType   the matched capability, or {@code null} for general chat
 * @param toolResults      per-tool-name results, empty when tool calling wasn't used
 * @param mergedContext    the text block built from {@code toolResults}, empty when unused
 * @param answer           the final answer text
 * @param usedToolCalling  {@code true} only when MCP tools were actually executed
 * @param usedSpringAi     {@code true} only when the final answer was synthesized via the Spring
 *                         AI foundation {@code ChatModel} rather than {@code AiGatewayService}
 * @param latencyMs        total time from decision to answer
 * @param fallbackReason   populated whenever {@code usedToolCalling} or {@code usedSpringAi} is
 *                         {@code false} despite a capability match — e.g. flag off, no MCP tools
 *                         registered, or a tool execution failure
 */
public record CapabilityResult(CapabilityType capabilityType, Map<String, McpToolResult> toolResults,
                                String mergedContext, String answer, boolean usedToolCalling,
                                boolean usedSpringAi, long latencyMs, String fallbackReason) {
}
