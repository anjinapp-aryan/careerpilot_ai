package ai.careerpilot.runtime;

import java.time.Instant;

/**
 * LangGraph Workflow Runtime — one timestamped step of an execution's {@link ExecutionTrace}
 * (e.g. "validated", "resolved definition", "invoked executor", "completed"). Rendered into
 * {@link WorkflowExecutionResult#executionLogs()} as plain strings; kept as a structured record
 * here so a future observability extension point (see {@link WorkflowMetrics}) can consume the
 * {@link #level()}/{@link #phase()} without re-parsing text.
 */
public record ExecutionEvent(Instant timestamp, String phase, String level, String message) {

    public static ExecutionEvent info(String phase, String message) {
        return new ExecutionEvent(Instant.now(), phase, "INFO", message);
    }

    public static ExecutionEvent warn(String phase, String message) {
        return new ExecutionEvent(Instant.now(), phase, "WARN", message);
    }

    public static ExecutionEvent error(String phase, String message) {
        return new ExecutionEvent(Instant.now(), phase, "ERROR", message);
    }

    @Override
    public String toString() {
        return "[" + timestamp + "] " + level + " " + phase + ": " + message;
    }
}
