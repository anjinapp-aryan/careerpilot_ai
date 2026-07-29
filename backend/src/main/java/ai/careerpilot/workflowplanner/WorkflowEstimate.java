package ai.careerpilot.workflowplanner;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * Phase 8 — planning-time estimates only, never measured from a real run (this package never
 * executes anything). Produced by {@link WorkflowEstimator} from a deterministic heuristic over
 * step count/type — not wired to {@code ai.careerpilot.ai.AiMetrics} or any real cost/usage data.
 */
public record WorkflowEstimate(
        Duration estimatedDuration,
        long approxTokenUsage,
        BigDecimal approxCost,
        int expectedAiCalls,
        int requiredMcpCalls,
        WorkflowComplexity complexity,
        double confidence) {
}
