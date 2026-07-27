package ai.careerpilot.planner;

import ai.careerpilot.capability.CapabilityType;

import java.util.Map;
import java.util.Set;

/**
 * Phase 11.2 — a dependency graph over {@link CapabilityType}s: {@code dependsOn.get(X)} is the
 * set of capabilities that must complete before {@code X} runs. Empty ({@link #none()}) is the
 * common case — most intents map to independent capabilities with no ordering requirement; a
 * dependency only exists where combining results genuinely benefits from sequencing (see {@code
 * DefaultCapabilityPlanner}'s `EXECUTIVE_COACH` example: job recommendations informed by an
 * already-computed career strategy).
 */
public record CapabilityDependencies(Map<CapabilityType, Set<CapabilityType>> dependsOn) {

    public static CapabilityDependencies none() {
        return new CapabilityDependencies(Map.of());
    }

    public Set<CapabilityType> dependenciesOf(CapabilityType type) {
        return dependsOn.getOrDefault(type, Set.of());
    }
}
