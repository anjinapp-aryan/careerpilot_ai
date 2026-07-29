package ai.careerpilot.runtime;

import ai.careerpilot.agent.AgentServiceClient;
import ai.careerpilot.api.dto.AgentServiceDtos.WorkflowDispatchResponse;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * LangGraph Workflow Runtime — the only real {@link WorkflowExecutor} today. Delegates to the
 * existing {@link AgentServiceClient} — it does not duplicate any HTTP/WebClient plumbing.
 * Deliberately does not go through {@code ai.careerpilot.service.WorkflowService} (the existing
 * 2D/3A caller of {@link AgentServiceClient}): that service assembles its payload from {@code
 * Resume}/{@code Job} rows, which is business logic this runtime is expressly forbidden from
 * knowing about. The payload built here is a generic envelope (mission/user/execution identifiers
 * plus the caller-supplied opaque {@code inputs} map) — a future phase that wires real business
 * input assembly as its own capability is what will make the payload shape match what any given
 * LangGraph node actually expects; until then, this executor is honest about being a transport
 * bridge, not a business integration.
 *
 * <h2>Phase 10A — dispatches by workflow id, not to the single main-graph endpoint</h2>
 * Calls {@link AgentServiceClient#startWorkflowRun(String, Map)} — {@code POST
 * /workflows/{workflowId}/runs} — using {@link WorkflowExecutionContext#definition()}'s resolved
 * business key. Any workflow the Python side has registered (see {@code
 * agent-service/app/dispatcher/registry.py}) is reachable this way; before Phase 10A this class
 * called {@link AgentServiceClient#startRun}, which only ever reaches the main career graph.
 *
 * <p><b>This class integrates with LangGraph; it does not implement LangGraph.</b> It contains no
 * node, edge, or graph-state logic of its own — it serializes a request, sends it over HTTP, and
 * deserializes whatever the Python AI Execution Plane returns. The name reflects which downstream
 * system it talks to (matching this codebase's existing convention of naming a provider after the
 * concrete system it integrates with, e.g. {@code GeminiProvider}, {@code NvidiaQwenProvider}),
 * not an implementation of that system inside Java.
 */
public class LangGraphWorkflowExecutor implements WorkflowExecutor {

    private final AgentServiceClient client;

    public LangGraphWorkflowExecutor(AgentServiceClient client) {
        this.client = client;
    }

    @Override
    public WorkflowExecutorOutcome execute(WorkflowExecutionContext context) {
        if (Thread.currentThread().isInterrupted()) {
            throw new WorkflowCancelledException(context.definition().workflowId(), context.executionId());
        }

        Map<String, Object> payload = buildPayload(context);
        WorkflowDispatchResponse response;
        try {
            response = client.startWorkflowRun(context.definition().workflowId(), payload);
        } catch (AgentServiceClient.AgentServiceException e) {
            if (isTimeout(e)) {
                throw new WorkflowTimeoutException(context.definition().workflowId(), context.executionId(), e);
            }
            throw new WorkflowExecutionException(context.definition().workflowId(), context.executionId(),
                    "LangGraph agent-service invocation failed: " + e.getMessage(), e);
        }

        if (response == null) {
            throw new WorkflowExecutionException(context.definition().workflowId(), context.executionId(),
                    "LangGraph agent-service returned an empty response", null);
        }

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("dispatchExecutionId", response.executionId());
        metadata.put("dispatchCorrelationId", response.correlationId());
        metadata.put("dispatchDurationMs", response.durationMs());
        List<String> pythonErrors = response.errors();
        if (pythonErrors != null && !pythonErrors.isEmpty()) {
            metadata.put("pythonErrors", pythonErrors);
        }

        Map<String, Object> output = response.output() == null ? Map.of() : response.output();
        return new WorkflowExecutorOutcome(mapStatus(response.status()), output, response.executionId(), metadata);
    }

    private Map<String, Object> buildPayload(WorkflowExecutionContext context) {
        WorkflowState state = context.state();
        Map<String, Object> payload = new HashMap<>();
        payload.put("mission_id", String.valueOf(context.missionId()));
        payload.put("user_id", String.valueOf(context.userId()));
        payload.put("execution_id", context.executionId());
        payload.put("correlation_id", context.correlationId());
        payload.put("inputs", state == null ? Map.of() : state.inputs());
        return payload;
    }

    private boolean isTimeout(AgentServiceClient.AgentServiceException e) {
        Throwable cause = e.getCause();
        return cause instanceof TimeoutException
                || (cause != null && cause.getClass().getSimpleName().toLowerCase(Locale.ROOT).contains("timeout"));
    }

    private WorkflowExecutionStatus mapStatus(String agentStatus) {
        if (agentStatus == null) {
            return WorkflowExecutionStatus.RUNNING;
        }
        return switch (agentStatus.toLowerCase(Locale.ROOT)) {
            case "completed", "complete", "success" -> WorkflowExecutionStatus.COMPLETED;
            case "failed", "error" -> WorkflowExecutionStatus.FAILED;
            case "interrupted" -> WorkflowExecutionStatus.INTERRUPTED;
            case "cancelled", "canceled" -> WorkflowExecutionStatus.CANCELLED;
            default -> WorkflowExecutionStatus.RUNNING;
        };
    }
}
