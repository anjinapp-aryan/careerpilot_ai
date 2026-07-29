package ai.careerpilot.runtime;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** LangGraph Workflow Runtime — the only {@link WorkflowResultMapper}. Pure data transformation, no branching business logic. */
public class DefaultWorkflowResultMapper implements WorkflowResultMapper {

    @Override
    public WorkflowExecutionResult mapOutcome(WorkflowExecutionContext context, WorkflowExecutorOutcome outcome,
                                               ExecutionTrace trace) {
        Instant start = trace.startTime();
        Instant end = trace.endTime() != null ? trace.endTime() : Instant.now();
        Map<String, Object> metrics = new HashMap<>(outcome.rawMetadata());
        metrics.put("workflowVersion", context.definition().version());
        if (context.executionDecision() != null) {
            metrics.put("executionPolicy", context.executionDecision().policy().name());
        }
        metrics.put("correlationId", context.correlationId());
        metrics.put("missionId", context.missionId().toString());
        metrics.put("retryCount", 0);

        return new WorkflowExecutionResult(context.definition().workflowId(), context.executionId(), outcome.status(),
                start, end, Duration.between(start, end), outcome.outputPayload(),
                logsOf(trace), warningsOf(trace), errorsOf(trace), metrics);
    }

    @Override
    public WorkflowExecutionResult mapFailure(String workflowId, UUID missionId, String executionId,
                                               ExecutionTrace trace, WorkflowExecutionStatus status,
                                               String errorMessage) {
        Instant start = trace.startTime() != null ? trace.startTime() : Instant.now();
        Instant end = trace.endTime() != null ? trace.endTime() : Instant.now();
        List<String> errors = new ArrayList<>(errorsOf(trace));
        if (errorMessage != null && errors.stream().noneMatch(e -> e.contains(errorMessage))) {
            errors.add(errorMessage);
        }
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("retryCount", 0);
        if (missionId != null) {
            metrics.put("missionId", missionId.toString());
        }

        return new WorkflowExecutionResult(workflowId, executionId, status, start, end, Duration.between(start, end),
                Map.of(), logsOf(trace), warningsOf(trace), errors, metrics);
    }

    private List<String> logsOf(ExecutionTrace trace) {
        return trace.events().stream().map(ExecutionEvent::toString).toList();
    }

    private List<String> warningsOf(ExecutionTrace trace) {
        return trace.events().stream().filter(e -> "WARN".equals(e.level())).map(ExecutionEvent::message).toList();
    }

    private List<String> errorsOf(ExecutionTrace trace) {
        return trace.events().stream().filter(e -> "ERROR".equals(e.level())).map(ExecutionEvent::message).toList();
    }
}
