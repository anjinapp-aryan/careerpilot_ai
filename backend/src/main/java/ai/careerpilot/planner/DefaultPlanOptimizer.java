package ai.careerpilot.planner;

import ai.careerpilot.capability.CapabilityType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phase 11.2 — the default {@link PlanOptimizer}: Kahn's algorithm (level-by-level topological
 * sort) restricted to dependencies that are actually part of this plan's own step set (a
 * dependency on a capability not in {@code steps} is ignored, not treated as unsatisfiable).
 * Within a stage, steps are ordered by {@link CapabilityPriority} (CRITICAL first), then by
 * {@link CapabilityType} name for determinism.
 *
 * <p><b>Cycle handling</b>: if dependencies form a cycle (should never happen with {@link
 * ai.careerpilot.planner.DefaultCapabilityPlanner}'s own static mapping, but a future dynamic
 * mapping could introduce one), this never loops forever or throws — any steps still unresolved
 * after the graph stops shrinking are placed into one final stage together, priority-sorted,
 * exactly as if they had no dependencies. A cycle degrades to "run everything remaining
 * together," never a crash.
 */
public class DefaultPlanOptimizer implements PlanOptimizer {

    private static final Logger log = LoggerFactory.getLogger(DefaultPlanOptimizer.class);

    private final CapabilityPlannerMetrics metrics;

    public DefaultPlanOptimizer(CapabilityPlannerMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public ExecutionOrder optimize(List<CapabilityStep> steps, CapabilityDependencies dependencies) {
        if (steps.isEmpty()) {
            return ExecutionOrder.empty();
        }

        Map<CapabilityType, CapabilityStep> stepByType = new HashMap<>();
        for (CapabilityStep step : steps) {
            stepByType.put(step.type(), step);
        }
        Set<CapabilityType> remaining = new HashSet<>(stepByType.keySet());

        List<List<CapabilityType>> stages = new ArrayList<>();
        while (!remaining.isEmpty()) {
            List<CapabilityType> ready = remaining.stream()
                    .filter(type -> dependencies.dependenciesOf(type).stream()
                            .filter(stepByType::containsKey)
                            .allMatch(dep -> !remaining.contains(dep)))
                    .sorted(Comparator
                            .comparing((CapabilityType t) -> stepByType.get(t).priority())
                            .thenComparing(Enum::name))
                    .toList();

            if (ready.isEmpty()) {
                // Cycle (or a dependency-only-on-itself edge case) — degrade rather than loop forever.
                log.warn("PlanOptimizer detected a dependency cycle among {}; running remaining steps in one stage", remaining);
                metrics.recordCycleDetected();
                stages.add(remaining.stream()
                        .sorted(Comparator.comparing((CapabilityType t) -> stepByType.get(t).priority())
                                .thenComparing(Enum::name))
                        .toList());
                break;
            }

            stages.add(ready);
            remaining.removeAll(ready);
        }

        return new ExecutionOrder(stages);
    }
}
