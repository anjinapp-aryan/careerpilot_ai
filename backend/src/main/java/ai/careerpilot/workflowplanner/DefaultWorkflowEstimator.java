package ai.careerpilot.workflowplanner;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

/**
 * Phase 8 — the only {@link WorkflowEstimator}. Deterministic heuristic, not measured: duration
 * sums each step's own estimate; token/cost/call counts scale with the number of steps that carry
 * a {@link ai.careerpilot.capability.CapabilityType} (a proxy for "this step would call an AI
 * capability"); complexity buckets on step count; confidence is a flat heuristic constant,
 * lowered slightly for {@link WorkflowPriority#CRITICAL} requests since urgency compresses the
 * time available to plan carefully. Not wired to any real usage/cost data.
 */
public class DefaultWorkflowEstimator implements WorkflowEstimator {

    private static final long TOKENS_PER_AI_STEP = 1500L;
    private static final BigDecimal COST_PER_AI_CALL = new BigDecimal("0.02");

    @Override
    public WorkflowEstimate estimate(WorkflowType type, List<WorkflowStep> allSteps, WorkflowPriority priority) {
        Duration totalDuration = allSteps.stream()
                .map(WorkflowStep::estimatedDuration)
                .reduce(Duration.ZERO, Duration::plus);

        long aiSteps = allSteps.stream().filter(s -> s.capability() != null).count();
        long tokenUsage = aiSteps * TOKENS_PER_AI_STEP;
        BigDecimal cost = COST_PER_AI_CALL.multiply(BigDecimal.valueOf(aiSteps));

        WorkflowComplexity complexity = allSteps.size() <= 3 ? WorkflowComplexity.LOW
                : allSteps.size() <= 6 ? WorkflowComplexity.MEDIUM : WorkflowComplexity.HIGH;

        double confidence = priority == WorkflowPriority.CRITICAL ? 0.5 : 0.65;

        return new WorkflowEstimate(totalDuration, tokenUsage, cost, (int) aiSteps, (int) aiSteps, complexity, confidence);
    }
}
