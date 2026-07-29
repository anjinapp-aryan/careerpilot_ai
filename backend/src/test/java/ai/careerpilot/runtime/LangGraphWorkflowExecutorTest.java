package ai.careerpilot.runtime;

import ai.careerpilot.agent.AgentServiceClient;
import ai.careerpilot.api.dto.AgentServiceDtos.WorkflowDispatchResponse;
import ai.careerpilot.missionexecution.ExecutionDecision;
import ai.careerpilot.missionexecution.ExecutionPolicy;
import ai.careerpilot.missionexecution.ExecutionPriority;
import ai.careerpilot.workflowplanner.WorkflowType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LangGraphWorkflowExecutorTest {

    private final AgentServiceClient client = mock(AgentServiceClient.class);
    private final LangGraphWorkflowExecutor executor = new LangGraphWorkflowExecutor(client);

    private WorkflowExecutionContext context() {
        ExecutionDecision decision = new ExecutionDecision(WorkflowType.RESUME, ExecutionPolicy.AUTO,
                ExecutionPriority.NORMAL, 1, List.of(), List.of(), null, "r");
        ResolvedWorkflowDefinition definition = new ResolvedWorkflowDefinition("RESUME_OPTIMIZATION_V1",
                "Resume Optimization", "v1", "RESUME_OPTIMIZATION", "ACTIVE");
        WorkflowState state = new WorkflowState(UUID.randomUUID(), UUID.randomUUID(), definition.workflowId(),
                "exec-1", Map.of(), Map.of("resumeVersion", "v3"), Map.of(), Map.of());
        return new WorkflowExecutionContext("exec-1", state.missionId(), state.userId(), decision, definition,
                state, "corr-1", Instant.now());
    }

    @Test
    void successfulRunMapsToCompletedOutcome() {
        when(client.startWorkflowRun(eq("RESUME_OPTIMIZATION_V1"), any())).thenReturn(new WorkflowDispatchResponse(
                "RESUME_OPTIMIZATION_V1", "exec-1", "corr-1", "completed", 42, Map.of("ats_score", 92), List.of()));

        WorkflowExecutorOutcome outcome = executor.execute(context());

        assertThat(outcome.status()).isEqualTo(WorkflowExecutionStatus.COMPLETED);
        assertThat(outcome.executionRef()).isEqualTo("exec-1");
        assertThat(outcome.outputPayload()).containsEntry("ats_score", 92);
        assertThat(outcome.rawMetadata()).containsEntry("dispatchDurationMs", 42L);
    }

    @Test
    void dispatchesByTheResolvedWorkflowIdNotAFixedEndpoint() {
        when(client.startWorkflowRun(anyString(), any())).thenReturn(new WorkflowDispatchResponse(
                "RESUME_OPTIMIZATION_V1", "exec-1", "corr-1", "completed", 1, Map.of(), List.of()));

        executor.execute(context());

        verify(client).startWorkflowRun(eq("RESUME_OPTIMIZATION_V1"), any());
    }

    @Test
    void interruptedStatusMapsToRunning() {
        when(client.startWorkflowRun(anyString(), any())).thenReturn(new WorkflowDispatchResponse(
                "RESUME_OPTIMIZATION_V1", "exec-1", "corr-1", "interrupted", 1, Map.of(), List.of()));

        WorkflowExecutorOutcome outcome = executor.execute(context());

        assertThat(outcome.status()).isEqualTo(WorkflowExecutionStatus.INTERRUPTED);
    }

    @Test
    void unrecognizedStatusDefaultsToRunning() {
        when(client.startWorkflowRun(anyString(), any())).thenReturn(new WorkflowDispatchResponse(
                "RESUME_OPTIMIZATION_V1", "exec-1", "corr-1", "queued", 1, Map.of(), List.of()));

        WorkflowExecutorOutcome outcome = executor.execute(context());

        assertThat(outcome.status()).isEqualTo(WorkflowExecutionStatus.RUNNING);
    }

    @Test
    void pythonErrorsAreCarriedIntoRawMetadataWhenPresent() {
        when(client.startWorkflowRun(anyString(), any())).thenReturn(new WorkflowDispatchResponse(
                "RESUME_OPTIMIZATION_V1", "exec-1", "corr-1", "error", 1, Map.of(),
                List.of("market_intelligence: all providers failed")));

        WorkflowExecutorOutcome outcome = executor.execute(context());

        assertThat(outcome.status()).isEqualTo(WorkflowExecutionStatus.FAILED);
        assertThat(outcome.rawMetadata()).containsEntry("pythonErrors", List.of("market_intelligence: all providers failed"));
    }

    @Test
    void agentServiceFailureBecomesWorkflowExecutionException() {
        when(client.startWorkflowRun(anyString(), any()))
                .thenThrow(new AgentServiceClient.AgentServiceException("boom", new RuntimeException("cause")));

        assertThatThrownBy(() -> executor.execute(context())).isInstanceOf(WorkflowExecutionException.class);
    }

    @Test
    void timeoutCauseBecomesWorkflowTimeoutException() {
        when(client.startWorkflowRun(anyString(), any()))
                .thenThrow(new AgentServiceClient.AgentServiceException("timeout", new TimeoutException("slow")));

        assertThatThrownBy(() -> executor.execute(context())).isInstanceOf(WorkflowTimeoutException.class);
    }

    @Test
    void nullResponseBecomesWorkflowExecutionException() {
        when(client.startWorkflowRun(anyString(), any())).thenReturn(null);

        assertThatThrownBy(() -> executor.execute(context())).isInstanceOf(WorkflowExecutionException.class);
    }

    @Test
    void interruptedThreadIsCancelledWithoutCallingClient() {
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> executor.execute(context())).isInstanceOf(WorkflowCancelledException.class);
            verify(client, never()).startWorkflowRun(anyString(), any());
        } finally {
            Thread.interrupted();
        }
    }
}
