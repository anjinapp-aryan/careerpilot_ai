package ai.careerpilot.missionexecution;

import ai.careerpilot.workflowplanner.WorkflowExecutionStrategy;
import ai.careerpilot.workflowplanner.WorkflowPlan;
import ai.careerpilot.workflowplanner.WorkflowType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Pre-Phase-9 Hardening — the only {@link MissionExecutionEngine}. For each {@link WorkflowPlan}
 * in the context: resolve priority → evaluate dependencies/preconditions → assign a policy
 * (BLOCKED if a dependency is unmet, RETRY if a previous attempt didn't complete, APPROVAL_REQUIRED
 * if the plan itself requires it, else PARALLEL/SEQUENTIAL/AUTO from the plan's own execution
 * strategy) → assign a week via {@link ExecutionScheduler} → derive an {@link ExpectedOutcome}.
 * Assembles the queues, validates, records to {@link ExecutionHistory}, and returns. Never
 * executes anything.
 */
public class DefaultMissionExecutionEngine implements MissionExecutionEngine {

    private final ExecutionPriorityResolver priorityResolver;
    private final ExecutionDependencyResolver dependencyResolver;
    private final ExecutionScheduler scheduler;
    private final ExecutionEstimator estimator;
    private final ExecutionValidator validator;
    private final ExecutionHistory history;

    public DefaultMissionExecutionEngine(ExecutionPriorityResolver priorityResolver,
                                          ExecutionDependencyResolver dependencyResolver,
                                          ExecutionScheduler scheduler, ExecutionEstimator estimator,
                                          ExecutionValidator validator, ExecutionHistory history) {
        this.priorityResolver = priorityResolver;
        this.dependencyResolver = dependencyResolver;
        this.scheduler = scheduler;
        this.estimator = estimator;
        this.validator = validator;
        this.history = history;
    }

    @Override
    public MissionExecutionPlan plan(ExecutionContext context) {
        List<WorkflowPlan> plans = context.workflowPlans();
        List<WorkflowType> requestedTypes = plans.stream().map(WorkflowPlan::workflowType).toList();
        Map<WorkflowType, Integer> weeks = scheduler.schedule(requestedTypes);

        List<ExecutionDecision> decisions = new ArrayList<>();
        for (WorkflowPlan wp : plans) {
            decisions.add(decide(wp, context, weeks.getOrDefault(wp.workflowType(), plans.size() + 1)));
        }

        List<WorkflowType> executionOrder = decisions.stream()
                .sorted(Comparator.comparingInt(ExecutionDecision::weekNumber)
                        .thenComparing(d -> d.priority().ordinal()))
                .map(ExecutionDecision::workflowType)
                .toList();

        List<ExecutionDecision> readyNow = decisions.stream()
                .filter(d -> d.weekNumber() == 1 && isRunnable(d.policy()))
                .toList();
        List<ExecutionDecision> scheduled = decisions.stream()
                .filter(d -> d.weekNumber() > 1 && isRunnable(d.policy()))
                .toList();
        List<ExecutionDecision> waiting = decisions.stream()
                .filter(d -> d.policy() == ExecutionPolicy.WAIT)
                .toList();
        ExecutionQueue executionQueue = new ExecutionQueue(readyNow, waiting, scheduled);

        List<ExecutionDecision> approvalQueue = decisions.stream()
                .filter(d -> d.policy() == ExecutionPolicy.APPROVAL_REQUIRED).toList();
        List<ExecutionDecision> blockedWorkflows = decisions.stream()
                .filter(d -> d.policy() == ExecutionPolicy.BLOCKED).toList();
        List<ExecutionDecision> retryQueue = decisions.stream()
                .filter(d -> d.policy() == ExecutionPolicy.RETRY).toList();
        List<ExecutionDecision> futureQueue = decisions.stream()
                .filter(d -> d.weekNumber() > 1).toList();

        List<ExecutionGroup> parallelGroups = decisions.stream()
                .filter(d -> d.policy() == ExecutionPolicy.PARALLEL)
                .collect(java.util.stream.Collectors.groupingBy(ExecutionDecision::weekNumber))
                .entrySet().stream()
                .map(e -> new ExecutionGroup(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingInt(ExecutionGroup::weekNumber))
                .toList();

        MissionExecutionEstimate estimate = estimator.estimate(plans, decisions);

        MissionExecutionPlan result = new MissionExecutionPlan(context.missionId(), decisions, executionQueue,
                executionOrder, parallelGroups, approvalQueue, blockedWorkflows, retryQueue, futureQueue,
                estimate, Instant.now());

        ExecutionValidationResult validation = validator.validate(result);
        if (!validation.valid()) {
            throw new ExecutionPlanningException("Invalid mission execution plan: " + validation.errors());
        }

        history.remember(result);
        return result;
    }

    private ExecutionDecision decide(WorkflowPlan wp, ExecutionContext context, int week) {
        WorkflowType type = wp.workflowType();
        ExecutionPriority priority = priorityResolver.resolve(wp, context);
        DependencyEvaluation dep = dependencyResolver.evaluate(type, context);
        boolean priorAttemptIncomplete = context.previousResults().stream()
                .anyMatch(r -> r.workflowType() == type && !r.completed());

        ExecutionPolicy policy;
        String reason;
        if (dep.blocked()) {
            policy = ExecutionPolicy.BLOCKED;
            reason = "Blocked by unmet dependency: prerequisite workflows " + dep.blockedByWorkflows()
                    + ", unmet preconditions " + dep.unmetPreconditions().stream().map(Precondition::description).toList();
        } else if (priorAttemptIncomplete) {
            policy = ExecutionPolicy.RETRY;
            reason = "A previous attempt at this workflow did not complete; queued for retry.";
        } else if (wp.approvalRequired()) {
            policy = ExecutionPolicy.APPROVAL_REQUIRED;
            reason = "Workflow plan requires human approval.";
        } else if (wp.executionStrategy() == WorkflowExecutionStrategy.PARALLEL) {
            policy = ExecutionPolicy.PARALLEL;
            reason = "Workflow plan has parallel-safe steps.";
        } else if (wp.executionStrategy() == WorkflowExecutionStrategy.SEQUENTIAL) {
            policy = ExecutionPolicy.SEQUENTIAL;
            reason = "Workflow plan is sequential with no blocking dependency.";
        } else {
            policy = ExecutionPolicy.AUTO;
            reason = "No blocking dependency; eligible to run automatically.";
        }

        ExpectedOutcome expected = deriveExpectedOutcome(wp, context.workflowPlans().size());
        return new ExecutionDecision(type, policy, priority, week, dep.blockedByWorkflows(), dep.unmetPreconditions(), expected, reason);
    }

    private static boolean isRunnable(ExecutionPolicy policy) {
        return policy == ExecutionPolicy.AUTO || policy == ExecutionPolicy.PARALLEL || policy == ExecutionPolicy.SEQUENTIAL;
    }

    private static ExpectedOutcome deriveExpectedOutcome(WorkflowPlan wp, int totalCount) {
        WorkflowType type = wp.workflowType();
        Double ats = (type == WorkflowType.ATS || type == WorkflowType.RESUME) ? 90.0 : null;
        Double interview = type == WorkflowType.INTERVIEW ? 80.0 : null;
        Double learning = type == WorkflowType.LEARNING ? 100.0 : null;
        double missionDelta = totalCount == 0 ? 0.0 : Math.round(1000.0 / totalCount) / 10.0;
        Double confidence = wp.estimate() == null ? null : wp.estimate().confidence();
        return new ExpectedOutcome(100.0, ats, interview, learning, missionDelta, confidence);
    }
}
