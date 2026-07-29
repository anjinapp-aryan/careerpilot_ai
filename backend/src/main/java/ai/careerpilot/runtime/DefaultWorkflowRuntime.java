package ai.careerpilot.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;

/**
 * LangGraph Workflow Runtime — the only {@link WorkflowRuntime}. A thin facade over small,
 * independently-testable collaborators: {@link ExecutionRequestValidator} → {@link
 * WorkflowRegistryAdapter} → {@link WorkflowContextFactory} → {@link WorkflowStateFactory} →
 * {@link WorkflowLifecycleManager#begin} → {@link WorkflowExecutor} → {@link
 * WorkflowLifecycleManager#complete}/{@code fail} → {@link WorkflowResultMapper} → {@link
 * WorkflowMetrics#record}. Contains no business logic and no AI reasoning of its own — every
 * branch here is about how far through the lifecycle an execution got and how to fail safely, not
 * about what a workflow means.
 *
 * <h2>Phase 10A — resolution is workflowId-driven, not decision-driven</h2>
 * Resolves via {@link WorkflowExecutionRequest#workflowId()} directly rather than requiring an
 * {@link ai.careerpilot.missionexecution.ExecutionDecision}. Any registered workflow can be run
 * this way — including one dispatched generically, with no {@code WorkflowType} enum entry.
 *
 * <h2>Never throws past its own boundary</h2>
 * A validation failure, a missing registry definition, or any exception the {@link
 * WorkflowExecutor} throws is caught here and turned into a terminal {@link WorkflowExecutionResult}
 * — errors are logged and recorded in the result's {@code errors} list, never silently discarded.
 * Callers of {@link #execute(WorkflowExecutionRequest)} never need a try/catch.
 */
public class DefaultWorkflowRuntime implements WorkflowRuntime {

    private static final Logger log = LoggerFactory.getLogger(DefaultWorkflowRuntime.class);

    private final ExecutionRequestValidator validator;
    private final WorkflowRegistryAdapter registryAdapter;
    private final WorkflowContextFactory contextFactory;
    private final WorkflowStateFactory stateFactory;
    private final WorkflowExecutor executor;
    private final WorkflowLifecycleManager lifecycleManager;
    private final WorkflowResultMapper resultMapper;
    private final WorkflowMetrics metrics;

    public DefaultWorkflowRuntime(ExecutionRequestValidator validator, WorkflowRegistryAdapter registryAdapter,
                                   WorkflowContextFactory contextFactory, WorkflowStateFactory stateFactory,
                                   WorkflowExecutor executor, WorkflowLifecycleManager lifecycleManager,
                                   WorkflowResultMapper resultMapper, WorkflowMetrics metrics) {
        this.validator = validator;
        this.registryAdapter = registryAdapter;
        this.contextFactory = contextFactory;
        this.stateFactory = stateFactory;
        this.executor = executor;
        this.lifecycleManager = lifecycleManager;
        this.resultMapper = resultMapper;
        this.metrics = metrics;
    }

    @Override
    public WorkflowExecutionResult execute(WorkflowExecutionRequest request) {
        String executionId = UUID.randomUUID().toString();
        String workflowId = request == null ? "UNKNOWN" : nullToUnknown(request.workflowId());
        UUID missionId = request == null ? null : request.missionId();

        List<String> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            log.warn("runtime_validation_failed: executionId={}, workflowId={}, missionId={}, violations={}",
                    executionId, workflowId, missionId, violations);
            return terminate(workflowId, missionId, executionId, WorkflowExecutionStatus.FAILED,
                    new ValidationException(violations));
        }

        ResolvedWorkflowDefinition definition;
        try {
            definition = registryAdapter.resolve(request.workflowId());
        } catch (WorkflowNotFoundException e) {
            log.warn("runtime_workflow_not_found: executionId={}, workflowId={}, missionId={}",
                    executionId, workflowId, missionId);
            return terminate(workflowId, missionId, executionId, WorkflowExecutionStatus.FAILED, e);
        }

        WorkflowExecutionContext context = contextFactory.create(request, definition, executionId);
        WorkflowState state = stateFactory.create(context, request.inputs());
        context = context.withState(state);

        ExecutionTrace trace = lifecycleManager.begin(context);
        lifecycleManager.recordPhase(trace, "VALIDATED", "Execution request validated");
        lifecycleManager.recordPhase(trace, "RESOLVED", "Resolved workflow definition " + definition.workflowId()
                + " v" + definition.version());
        lifecycleManager.recordPhase(trace, "CONTEXT_BUILT", "Execution context and state built");

        log.info("runtime_execution_started: executionId={}, workflowId={}, missionId={}, correlationId={}",
                executionId, definition.workflowId(), missionId, context.correlationId());

        try {
            lifecycleManager.recordPhase(trace, "INVOKING", "Invoking workflow executor");
            WorkflowExecutorOutcome outcome = executor.execute(context);
            lifecycleManager.complete(trace, outcome);
            WorkflowExecutionResult result = resultMapper.mapOutcome(context, outcome, trace);
            log.info("runtime_execution_completed: executionId={}, workflowId={}, missionId={}, status={}, durationMs={}",
                    executionId, definition.workflowId(), missionId, result.executionStatus(),
                    result.duration() == null ? -1 : result.duration().toMillis());
            metrics.record(result);
            return result;
        } catch (WorkflowTimeoutException e) {
            return failAndRecord(definition.workflowId(), missionId, executionId, trace, WorkflowExecutionStatus.TIMED_OUT, "TIMEOUT", e);
        } catch (WorkflowCancelledException e) {
            return failAndRecord(definition.workflowId(), missionId, executionId, trace, WorkflowExecutionStatus.CANCELLED, "CANCELLED", e);
        } catch (WorkflowExecutionException e) {
            return failAndRecord(definition.workflowId(), missionId, executionId, trace, WorkflowExecutionStatus.FAILED, "EXECUTION_FAILED", e);
        } catch (RuntimeException e) {
            return failAndRecord(definition.workflowId(), missionId, executionId, trace, WorkflowExecutionStatus.FAILED, "UNEXPECTED_ERROR", e);
        }
    }

    private WorkflowExecutionResult failAndRecord(String workflowId, UUID missionId, String executionId,
                                                    ExecutionTrace trace, WorkflowExecutionStatus status,
                                                    String phase, Exception e) {
        log.error("runtime_execution_failed: executionId={}, workflowId={}, missionId={}, status={}, phase={}, error={}",
                executionId, workflowId, missionId, status, phase, e.toString());
        lifecycleManager.fail(trace, phase, e);
        WorkflowExecutionResult result = resultMapper.mapFailure(workflowId, missionId, executionId, trace, status, e.getMessage());
        metrics.record(result);
        return result;
    }

    private WorkflowExecutionResult terminate(String workflowId, UUID missionId, String executionId,
                                               WorkflowExecutionStatus status, Exception e) {
        ExecutionTrace trace = new ExecutionTrace();
        trace.start();
        trace.record(ExecutionEvent.error("REJECTED", e.getMessage()));
        trace.end();
        WorkflowExecutionResult result = resultMapper.mapFailure(workflowId, missionId, executionId, trace, status, e.getMessage());
        metrics.record(result);
        return result;
    }

    private String nullToUnknown(String workflowId) {
        return workflowId == null || workflowId.isBlank() ? "UNKNOWN" : workflowId;
    }
}
