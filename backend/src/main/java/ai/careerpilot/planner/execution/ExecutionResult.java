package ai.careerpilot.planner.execution;

import ai.careerpilot.capability.CapabilityType;
import ai.careerpilot.mcp.McpToolResult;

import java.util.Map;

/**
 * Phase 11.3 — the outcome of executing one {@link ai.careerpilot.planner.CapabilityStep}
 * (i.e. one {@link CapabilityType}, which may itself have resolved to several MCP tools via
 * {@code ToolSelectionEngine}). {@code success} is {@code true} only when every resolved tool
 * succeeded on the final attempt; a partial per-tool failure still returns a result (never
 * throws) — see {@code DefaultCapabilityExecutor}.
 *
 * @param capabilityType the capability this result is for
 * @param toolResults    per-tool-name results from the final attempt
 * @param success        whether every tool in {@code toolResults} succeeded
 * @param attempts       how many attempts were made (1 = succeeded/failed on the first try)
 * @param latencyMs      total time across all attempts
 * @param error          populated when {@code success} is {@code false}; a short summary, not a stack trace
 */
public record ExecutionResult(CapabilityType capabilityType, Map<String, McpToolResult> toolResults,
                               boolean success, int attempts, long latencyMs, String error) {
}
