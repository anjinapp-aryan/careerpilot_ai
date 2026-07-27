package ai.careerpilot.planner;

import java.util.List;

/**
 * Phase 11.2 — resolves an unordered set of {@link CapabilityStep}s plus their {@link
 * CapabilityDependencies} into a stage-by-stage {@link ExecutionOrder} safe for parallel
 * execution within each stage.
 */
public interface PlanOptimizer {

    ExecutionOrder optimize(List<CapabilityStep> steps, CapabilityDependencies dependencies);
}
