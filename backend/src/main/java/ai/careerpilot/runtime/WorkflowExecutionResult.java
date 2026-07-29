package ai.careerpilot.runtime;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * LangGraph Workflow Runtime — the sole output of {@link WorkflowRuntime#execute}. Terminal for
 * every request the runtime accepts, including ones that never reached the executor (a validation
 * or resolution failure still produces a fully-formed result with {@link #executionStatus()}
 * {@code FAILED} and a populated {@link #errors()} list — the runtime never throws past its own
 * boundary; see {@link DefaultWorkflowRuntime}).
 */
public record WorkflowExecutionResult(String workflowId, String executionId, WorkflowExecutionStatus executionStatus,
                                       Instant startTime, Instant endTime, Duration duration,
                                       Map<String, Object> outputPayload, List<String> executionLogs,
                                       List<String> warnings, List<String> errors, Map<String, Object> metrics) {

    public WorkflowExecutionResult {
        outputPayload = outputPayload == null ? Map.of() : Map.copyOf(outputPayload);
        executionLogs = executionLogs == null ? List.of() : List.copyOf(executionLogs);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        errors = errors == null ? List.of() : List.copyOf(errors);
        metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
    }

    public boolean successful() {
        return executionStatus == WorkflowExecutionStatus.COMPLETED;
    }
}
