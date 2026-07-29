package ai.careerpilot.runtime;

import java.util.Map;

/**
 * LangGraph Workflow Runtime — the observability extension point. Deliberately narrow: one method,
 * called once per completed {@link WorkflowExecutionResult}. Per this phase's explicit scope ("do
 * not implement distributed tracing, design extension points only"), {@link InMemoryWorkflowMetrics}
 * is hand-rolled counters (same style as {@code ai.careerpilot.ai.AiMetrics}/{@code
 * ai.careerpilot.mcp.InMemoryMcpMetrics}), not a tracing/Micrometer integration. AI provider and
 * token usage are named in the phase spec as future fields — {@link #snapshot()} exposes a stable
 * key set today (with those two keys absent until a future phase's executor actually knows them)
 * so a future implementation can add them without changing this interface.
 */
public interface WorkflowMetrics {

    void record(WorkflowExecutionResult result);

    Map<String, Object> snapshot();
}
