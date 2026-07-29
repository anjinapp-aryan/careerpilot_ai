package ai.careerpilot.runtime;

import ai.careerpilot.missionexecution.ExecutionDecision;
import ai.careerpilot.missionexecution.ExecutionPolicy;
import ai.careerpilot.missionexecution.ExecutionPriority;
import ai.careerpilot.workflowplanner.WorkflowType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sociable unit test: wires the real default collaborators (validator, context/state factories,
 * lifecycle manager, result mapper, a real {@link InMemoryWorkflowMetrics}) but mocks {@link
 * WorkflowRegistryAdapter} and {@link WorkflowExecutor} — the two collaborators that would
 * otherwise need a database or a live agent-service — so {@link DefaultWorkflowRuntime}'s own
 * branching logic (validation/resolution/timeout/cancellation/failure/success) is what's pinned.
 */
class DefaultWorkflowRuntimeTest {

    private final WorkflowRegistryAdapter registryAdapter = mock(WorkflowRegistryAdapter.class);
    private final WorkflowExecutor executor = mock(WorkflowExecutor.class);
    private final InMemoryWorkflowMetrics metrics = new InMemoryWorkflowMetrics();

    private final DefaultWorkflowRuntime runtime = new DefaultWorkflowRuntime(
            new DefaultExecutionRequestValidator(), registryAdapter, new DefaultWorkflowContextFactory(),
            new DefaultWorkflowStateFactory(), executor, new DefaultWorkflowLifecycleManager(),
            new DefaultWorkflowResultMapper(), metrics);

    private final ResolvedWorkflowDefinition definition = new ResolvedWorkflowDefinition(
            "RESUME_OPTIMIZATION_V1", "Resume Optimization", "v1", "RESUME_OPTIMIZATION", "ACTIVE");

    private ExecutionDecision decision(ExecutionPolicy policy) {
        return new ExecutionDecision(WorkflowType.RESUME, policy, ExecutionPriority.NORMAL, 1,
                List.of(), List.of(), null, "r");
    }

    private WorkflowExecutionRequest request(ExecutionPolicy policy) {
        return WorkflowExecutionRequest.forDecision(UUID.randomUUID(), UUID.randomUUID(), decision(policy),
                Map.of("resumeVersion", "v3"), "corr-1");
    }

    @Test
    void validationFailureNeverReachesRegistryOrExecutor() {
        WorkflowExecutionResult result = runtime.execute(request(ExecutionPolicy.BLOCKED));

        assertThat(result.executionStatus()).isEqualTo(WorkflowExecutionStatus.FAILED);
        assertThat(result.errors()).isNotEmpty();
        assertThat(result.executionId()).isNotBlank();
        verify(registryAdapter, never()).resolve(org.mockito.ArgumentMatchers.anyString());
        verify(executor, never()).execute(any());
    }

    @Test
    void nullRequestProducesAFailedResultRatherThanThrowing() {
        WorkflowExecutionResult result = runtime.execute(null);

        assertThat(result.executionStatus()).isEqualTo(WorkflowExecutionStatus.FAILED);
        assertThat(result.workflowId()).isEqualTo("UNKNOWN");
    }

    @Test
    void registryMissResultsInFailedResultBeforeExecutorIsCalled() {
        when(registryAdapter.resolve("RESUME_OPTIMIZATION")).thenThrow(new WorkflowNotFoundException("RESUME_OPTIMIZATION"));

        WorkflowExecutionResult result = runtime.execute(request(ExecutionPolicy.AUTO));

        assertThat(result.executionStatus()).isEqualTo(WorkflowExecutionStatus.FAILED);
        verify(executor, never()).execute(any());
    }

    @Test
    void successfulExecutionProducesACompletedResultAndRecordsMetrics() {
        when(registryAdapter.resolve("RESUME_OPTIMIZATION")).thenReturn(definition);
        when(executor.execute(any())).thenReturn(new WorkflowExecutorOutcome(WorkflowExecutionStatus.COMPLETED,
                Map.of("ats_score", 90), "thread-1", Map.of("thread_id", "thread-1")));

        WorkflowExecutionResult result = runtime.execute(request(ExecutionPolicy.AUTO));

        assertThat(result.executionStatus()).isEqualTo(WorkflowExecutionStatus.COMPLETED);
        assertThat(result.workflowId()).isEqualTo("RESUME_OPTIMIZATION_V1");
        assertThat(result.outputPayload()).containsEntry("ats_score", 90);
        assertThat(result.executionLogs()).isNotEmpty();
        assertThat(metrics.snapshot().get("totalExecutions")).isEqualTo(1L);
    }

    @Test
    void executorTimeoutBecomesATimedOutResult() {
        when(registryAdapter.resolve("RESUME_OPTIMIZATION")).thenReturn(definition);
        when(executor.execute(any())).thenThrow(new WorkflowTimeoutException("RESUME_OPTIMIZATION_V1", "exec-1", new RuntimeException()));

        WorkflowExecutionResult result = runtime.execute(request(ExecutionPolicy.AUTO));

        assertThat(result.executionStatus()).isEqualTo(WorkflowExecutionStatus.TIMED_OUT);
    }

    @Test
    void executorCancellationBecomesACancelledResult() {
        when(registryAdapter.resolve("RESUME_OPTIMIZATION")).thenReturn(definition);
        when(executor.execute(any())).thenThrow(new WorkflowCancelledException("RESUME_OPTIMIZATION_V1", "exec-1"));

        WorkflowExecutionResult result = runtime.execute(request(ExecutionPolicy.AUTO));

        assertThat(result.executionStatus()).isEqualTo(WorkflowExecutionStatus.CANCELLED);
    }

    @Test
    void executorFailureBecomesAFailedResultWithErrorRecorded() {
        when(registryAdapter.resolve("RESUME_OPTIMIZATION")).thenReturn(definition);
        when(executor.execute(any())).thenThrow(new WorkflowExecutionException("RESUME_OPTIMIZATION_V1", "exec-1",
                "agent-service unavailable", null));

        WorkflowExecutionResult result = runtime.execute(request(ExecutionPolicy.AUTO));

        assertThat(result.executionStatus()).isEqualTo(WorkflowExecutionStatus.FAILED);
        assertThat(result.errors()).anyMatch(e -> e.contains("agent-service unavailable"));
    }

    @Test
    void unexpectedRuntimeExceptionIsNeverSwallowedAndBecomesAFailedResult() {
        when(registryAdapter.resolve("RESUME_OPTIMIZATION")).thenReturn(definition);
        when(executor.execute(any())).thenThrow(new IllegalStateException("unexpected"));

        WorkflowExecutionResult result = runtime.execute(request(ExecutionPolicy.AUTO));

        assertThat(result.executionStatus()).isEqualTo(WorkflowExecutionStatus.FAILED);
        assertThat(result.errors()).anyMatch(e -> e.contains("unexpected"));
    }

    @Test
    void aGenericRequestWithNoExecutionDecisionResolvesAndExecutesSuccessfully() {
        // Phase 10A: workflowId alone (no ExecutionDecision) is enough to resolve and run.
        WorkflowExecutionRequest generic = new WorkflowExecutionRequest(UUID.randomUUID(), UUID.randomUUID(),
                "SKILL_GAP_INTELLIGENCE", null, Map.of("targetRole", "Senior Java Architect"), "corr-generic");
        when(registryAdapter.resolve("SKILL_GAP_INTELLIGENCE")).thenReturn(definition);
        when(executor.execute(any())).thenReturn(new WorkflowExecutorOutcome(WorkflowExecutionStatus.COMPLETED,
                Map.of("readinessScore", 78), "exec-ref", Map.of()));

        WorkflowExecutionResult result = runtime.execute(generic);

        assertThat(result.executionStatus()).isEqualTo(WorkflowExecutionStatus.COMPLETED);
        assertThat(result.outputPayload()).containsEntry("readinessScore", 78);
        assertThat(result.metrics()).doesNotContainKey("executionPolicy");
    }

    @Test
    void aGenericRequestWithBlankWorkflowIdFailsValidation() {
        WorkflowExecutionRequest generic = new WorkflowExecutionRequest(UUID.randomUUID(), UUID.randomUUID(),
                "", null, Map.of(), "corr-generic");

        WorkflowExecutionResult result = runtime.execute(generic);

        assertThat(result.executionStatus()).isEqualTo(WorkflowExecutionStatus.FAILED);
        verify(registryAdapter, never()).resolve(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void everyExecutionGetsAUniqueExecutionId() {
        when(registryAdapter.resolve("RESUME_OPTIMIZATION")).thenReturn(definition);
        when(executor.execute(any())).thenReturn(new WorkflowExecutorOutcome(WorkflowExecutionStatus.COMPLETED,
                Map.of(), "ref", Map.of()));

        WorkflowExecutionResult first = runtime.execute(request(ExecutionPolicy.AUTO));
        WorkflowExecutionResult second = runtime.execute(request(ExecutionPolicy.AUTO));

        assertThat(first.executionId()).isNotEqualTo(second.executionId());
    }
}
