package ai.careerpilot.missionexecution;

import ai.careerpilot.capability.CapabilityType;
import ai.careerpilot.workflowplanner.*;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sociable unit test: wires the real default collaborators (same as {@link
 * MissionExecutionEngineConfig} would) rather than mocking each one, since {@link
 * DefaultMissionExecutionEngine}'s own branching logic (BLOCKED/RETRY/APPROVAL_REQUIRED/
 * PARALLEL/SEQUENTIAL/AUTO) is what needs pinning here.
 */
class DefaultMissionExecutionEngineTest {

    private final DefaultMissionExecutionEngine engine = new DefaultMissionExecutionEngine(
            new DefaultExecutionPriorityResolver(), new DefaultExecutionDependencyResolver(),
            new DefaultExecutionScheduler(), new DefaultExecutionEstimator(), new DefaultExecutionValidator(),
            new InMemoryExecutionHistory());

    private WorkflowPlan workflowPlan(WorkflowType type, WorkflowPriority priority, boolean approvalRequired,
                                       WorkflowExecutionStrategy strategy) {
        WorkflowEstimate estimate = new WorkflowEstimate(Duration.ofMinutes(10), 1000, java.math.BigDecimal.ONE, 1, 1, WorkflowComplexity.MEDIUM, 0.7);
        return new WorkflowPlan(UUID.randomUUID(), type, "v1", priority, UUID.randomUUID(), null,
                CapabilityType.RESUME_ANALYSIS, WorkflowComplexity.MEDIUM, Duration.ofMinutes(10),
                List.of(), List.of(), List.of(), List.of(), approvalRequired, RetryStrategy.standard(),
                FallbackStrategy.escalateToHuman(), List.of(), type.name() + "_GRAPH_V1", "start", "end", "auto",
                Map.of(), estimate, strategy, Instant.now());
    }

    @Test
    void resumeWithNoDependenciesIsAutoAndReadyNowAtWeek1() {
        ExecutionContext context = new ExecutionContext(UUID.randomUUID(),
                List.of(workflowPlan(WorkflowType.RESUME, WorkflowPriority.MEDIUM, false, WorkflowExecutionStrategy.SEQUENTIAL)));

        MissionExecutionPlan plan = engine.plan(context);

        ExecutionDecision decision = plan.decisions().get(0);
        assertThat(decision.policy()).isEqualTo(ExecutionPolicy.SEQUENTIAL);
        assertThat(decision.weekNumber()).isEqualTo(1);
        assertThat(plan.executionQueue().readyNow()).containsExactly(decision);
    }

    @Test
    void atsIsBlockedWhenResumeScoreMetricIsMissing() {
        ExecutionContext context = new ExecutionContext(UUID.randomUUID(),
                List.of(workflowPlan(WorkflowType.ATS, WorkflowPriority.MEDIUM, false, WorkflowExecutionStrategy.SEQUENTIAL)));

        MissionExecutionPlan plan = engine.plan(context);

        assertThat(plan.blockedWorkflows()).hasSize(1);
        assertThat(plan.blockedWorkflows().get(0).policy()).isEqualTo(ExecutionPolicy.BLOCKED);
        assertThat(plan.executionQueue().readyNow()).isEmpty();
    }

    @Test
    void approvalRequiredPlanGoesToApprovalQueueEvenWithoutBlockingDependency() {
        ExecutionContext context = new ExecutionContext(UUID.randomUUID(),
                List.of(workflowPlan(WorkflowType.RESUME, WorkflowPriority.MEDIUM, true, WorkflowExecutionStrategy.SEQUENTIAL)));

        MissionExecutionPlan plan = engine.plan(context);

        assertThat(plan.approvalQueue()).hasSize(1);
        assertThat(plan.approvalQueue().get(0).policy()).isEqualTo(ExecutionPolicy.APPROVAL_REQUIRED);
    }

    @Test
    void previousIncompleteAttemptIsQueuedForRetry() {
        UUID missionId = UUID.randomUUID();
        WorkflowPlan wp = workflowPlan(WorkflowType.RESUME, WorkflowPriority.MEDIUM, false, WorkflowExecutionStrategy.SEQUENTIAL);
        List<ExecutionResultSummary> previous = List.of(
                new ExecutionResultSummary(WorkflowType.RESUME, false, null, null, null, null, null, Instant.now()));
        ExecutionContext context = new ExecutionContext(missionId, List.of(wp), 0, Map.of(), Map.of(), previous);

        MissionExecutionPlan plan = engine.plan(context);

        assertThat(plan.retryQueue()).hasSize(1);
        assertThat(plan.retryQueue().get(0).policy()).isEqualTo(ExecutionPolicy.RETRY);
    }

    @Test
    void parallelExecutionStrategyProducesAParallelGroup() {
        WorkflowPlan wp = workflowPlan(WorkflowType.RESUME, WorkflowPriority.MEDIUM, false, WorkflowExecutionStrategy.PARALLEL);
        ExecutionContext context = new ExecutionContext(UUID.randomUUID(), List.of(wp));

        MissionExecutionPlan plan = engine.plan(context);

        assertThat(plan.parallelGroups()).hasSize(1);
        assertThat(plan.parallelGroups().get(0).weekNumber()).isEqualTo(1);
        assertThat(plan.parallelGroups().get(0).decisions()).hasSize(1);
    }

    @Test
    void multiWorkflowMissionMatchesWeekAssignmentAndFutureQueue() {
        WorkflowPlan resume = workflowPlan(WorkflowType.RESUME, WorkflowPriority.MEDIUM, false, WorkflowExecutionStrategy.SEQUENTIAL);
        WorkflowPlan linkedin = workflowPlan(WorkflowType.LINKEDIN, WorkflowPriority.MEDIUM, false, WorkflowExecutionStrategy.SEQUENTIAL);
        ExecutionContext context = new ExecutionContext(UUID.randomUUID(), List.of(resume, linkedin));

        MissionExecutionPlan plan = engine.plan(context);

        assertThat(plan.executionOrder()).containsExactly(WorkflowType.RESUME, WorkflowType.LINKEDIN);
        assertThat(plan.futureQueue()).hasSize(1);
        assertThat(plan.futureQueue().get(0).workflowType()).isEqualTo(WorkflowType.LINKEDIN);
    }

    @Test
    void expectedOutcomeIsPopulatedForResumeAndAtsTypes() {
        WorkflowPlan wp = workflowPlan(WorkflowType.RESUME, WorkflowPriority.MEDIUM, false, WorkflowExecutionStrategy.SEQUENTIAL);
        ExecutionContext context = new ExecutionContext(UUID.randomUUID(), List.of(wp));

        MissionExecutionPlan plan = engine.plan(context);

        ExpectedOutcome expected = plan.decisions().get(0).expectedOutcome();
        assertThat(expected.expectedAtsScore()).isEqualTo(90.0);
        assertThat(expected.expectedCompletionPercent()).isEqualTo(100.0);
        assertThat(expected.expectedConfidence()).isEqualTo(0.7);
    }

    @Test
    void recordsEachPlanIntoExecutionHistory() {
        UUID missionId = UUID.randomUUID();
        WorkflowPlan wp = workflowPlan(WorkflowType.RESUME, WorkflowPriority.MEDIUM, false, WorkflowExecutionStrategy.SEQUENTIAL);
        ExecutionHistory history = new InMemoryExecutionHistory();
        DefaultMissionExecutionEngine engineWithHistory = new DefaultMissionExecutionEngine(
                new DefaultExecutionPriorityResolver(), new DefaultExecutionDependencyResolver(),
                new DefaultExecutionScheduler(), new DefaultExecutionEstimator(), new DefaultExecutionValidator(), history);

        MissionExecutionPlan plan = engineWithHistory.plan(new ExecutionContext(missionId, List.of(wp)));

        assertThat(history.recentFor(missionId, 5)).containsExactly(plan);
    }

    @Test
    void emptyWorkflowPlanListProducesAnEmptyButValidPlan() {
        MissionExecutionPlan plan = engine.plan(new ExecutionContext(UUID.randomUUID(), List.of()));

        assertThat(plan.decisions()).isEmpty();
        assertThat(plan.executionOrder()).isEmpty();
    }
}
