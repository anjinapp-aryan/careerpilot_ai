package ai.careerpilot.mcp;

import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Phase 10.1 — extension-point only. The contract a future MCP tool-execution engine would
 * implement to actually invoke a tool against its owning server. <b>No implementation exists
 * in this codebase</b> — no bean implements this interface, nothing connects to a real MCP
 * server. Returns {@link Mono} to match this codebase's existing reactive AI-call conventions
 * (see {@code ai.careerpilot.ai.LlmProvider#streamChat}), not because any reactive transport is
 * wired here.
 *
 * <p>When a real implementation eventually exists, LangGraph agents and business services are
 * expected to call it instead of a concrete tool integration — see the package javadoc's
 * "LangGraph Integration Points" section.
 */
public interface McpExecutor {

    Mono<McpToolResult> execute(McpToolDefinition tool, Map<String, Object> arguments, McpExecutionContext context);
}
