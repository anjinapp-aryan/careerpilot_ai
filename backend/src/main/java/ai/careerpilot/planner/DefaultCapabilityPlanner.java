package ai.careerpilot.planner;

import ai.careerpilot.capability.CapabilityType;
import ai.careerpilot.intent.IntentResult;
import ai.careerpilot.intent.IntentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phase 11.2 — the default {@link CapabilityPlanner}. Static {@code IntentType → CapabilityType}
 * mapping today (six of the seven Phase 11.1 intents map 1:1 onto an existing Phase 10.3
 * capability of the same shape; {@code EXECUTIVE_COACH} — which has no dedicated capability yet
 * — maps to two: career strategy plus job recommendation, with job recommendation depending on
 * career strategy completing first, the one worked "multi-capability with a real dependency"
 * example in this phase). A future phase could make this mapping dynamic/configurable without
 * changing this interface.
 *
 * <p>Never throws: {@code intentResult.intentType() == null} (no intent matched, or the
 * upstream Phase 11.1 engine itself fell back) and an unmapped intent both produce {@code
 * CapabilityPlan.empty(...)} rather than an exception.
 */
public class DefaultCapabilityPlanner implements CapabilityPlanner {

    private static final Logger log = LoggerFactory.getLogger(DefaultCapabilityPlanner.class);

    private static final Map<IntentType, List<CapabilityStep>> INTENT_TO_STEPS = Map.ofEntries(
            Map.entry(IntentType.RESUME_ANALYSIS, List.of(
                    new CapabilityStep(CapabilityType.RESUME_ANALYSIS, CapabilityPriority.HIGH))),
            Map.entry(IntentType.CAREER_STRATEGY, List.of(
                    new CapabilityStep(CapabilityType.CAREER_STRATEGY, CapabilityPriority.HIGH))),
            Map.entry(IntentType.INTERVIEW_PREPARATION, List.of(
                    new CapabilityStep(CapabilityType.INTERVIEW_PREPARATION, CapabilityPriority.HIGH))),
            Map.entry(IntentType.GITHUB_ANALYSIS, List.of(
                    new CapabilityStep(CapabilityType.GITHUB_REVIEW, CapabilityPriority.HIGH))),
            Map.entry(IntentType.JOB_RECOMMENDATION, List.of(
                    new CapabilityStep(CapabilityType.JOB_RECOMMENDATION, CapabilityPriority.HIGH))),
            Map.entry(IntentType.LEARNING_HELP, List.of(
                    new CapabilityStep(CapabilityType.LEARNING_HELP, CapabilityPriority.HIGH))),
            Map.entry(IntentType.EXECUTIVE_COACH, List.of(
                    new CapabilityStep(CapabilityType.CAREER_STRATEGY, CapabilityPriority.HIGH),
                    new CapabilityStep(CapabilityType.JOB_RECOMMENDATION, CapabilityPriority.MEDIUM))));

    private static final Map<IntentType, CapabilityDependencies> INTENT_TO_DEPENDENCIES = Map.of(
            IntentType.EXECUTIVE_COACH, new CapabilityDependencies(
                    Map.of(CapabilityType.JOB_RECOMMENDATION, Set.of(CapabilityType.CAREER_STRATEGY))));

    private final PlanOptimizer optimizer;
    private final CapabilityPlannerMetrics metrics;

    public DefaultCapabilityPlanner(PlanOptimizer optimizer, CapabilityPlannerMetrics metrics) {
        this.optimizer = optimizer;
        this.metrics = metrics;
    }

    @Override
    public CapabilityPlan plan(IntentResult intentResult) {
        long start = System.currentTimeMillis();
        CapabilityPlan plan = doPlan(intentResult);
        metrics.recordPlanLatency(System.currentTimeMillis() - start);
        metrics.recordPlanSize(plan.steps().size());
        return plan;
    }

    private CapabilityPlan doPlan(IntentResult intentResult) {
        if (intentResult == null || intentResult.intentType() == null) {
            return CapabilityPlan.empty("no intent matched");
        }
        IntentType type = intentResult.intentType();
        List<CapabilityStep> steps = INTENT_TO_STEPS.get(type);
        if (steps == null || steps.isEmpty()) {
            return CapabilityPlan.empty("no capability mapping registered for intent " + type);
        }

        CapabilityDependencies dependencies = INTENT_TO_DEPENDENCIES.getOrDefault(type, CapabilityDependencies.none());
        ExecutionOrder order;
        try {
            order = optimizer.optimize(steps, dependencies);
        } catch (Exception e) {
            log.warn("PlanOptimizer failed for intent {}, falling back to a single unordered stage: {}", type, e.toString());
            order = new ExecutionOrder(List.of(steps.stream().map(CapabilityStep::type).toList()));
        }

        return new CapabilityPlan(type, steps, dependencies, order, "planned for intent " + type);
    }
}
