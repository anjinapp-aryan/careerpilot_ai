package ai.careerpilot.workflowplanner;

import ai.careerpilot.capability.CapabilityType;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 8 — the sole output of {@link WorkflowPlanner#plan}. A plan is data: it describes HOW a
 * mission recommendation should execute (steps, strategy, estimates, approval/retry/fallback
 * policy) — it never executes anything itself, and nothing in this package calls Spring AI,
 * MCP, LangGraph, or the AI Gateway to produce one. {@code futureLangGraphGraphId}/{@code
 * futureLangGraphEntryNode}/{@code futureLangGraphExitNode} and {@code futureSpringAiModelHint}
 * are forward-looking hints only, consumed by nothing today — see the package javadoc for why
 * this dependency direction (hints only, no library dependency) is deliberate.
 */
public record WorkflowPlan(
        UUID workflowId,
        WorkflowType workflowType,
        String version,
        WorkflowPriority priority,
        UUID missionId,
        UUID strategyId,
        CapabilityType capabilityType,
        WorkflowComplexity estimatedComplexity,
        Duration estimatedDuration,
        List<WorkflowStep> sequentialSteps,
        List<WorkflowStep> parallelSteps,
        List<String> requiredInputs,
        List<String> expectedOutputs,
        boolean approvalRequired,
        RetryStrategy retryStrategy,
        FallbackStrategy fallbackStrategy,
        List<String> requiredMcpCapabilities,
        String futureLangGraphGraphId,
        String futureLangGraphEntryNode,
        String futureLangGraphExitNode,
        String futureSpringAiModelHint,
        Map<String, Object> metadata,
        WorkflowEstimate estimate,
        WorkflowExecutionStrategy executionStrategy,
        Instant createdAt) {

    public List<WorkflowStep> allSteps() {
        return java.util.stream.Stream.concat(sequentialSteps.stream(), parallelSteps.stream()).toList();
    }
}
