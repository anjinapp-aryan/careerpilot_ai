package ai.careerpilot.planner;

import ai.careerpilot.capability.CapabilityType;

/**
 * Phase 11.2 — one node in a {@link CapabilityPlan}: a single {@code CapabilityType} (Phase
 * 10.3's existing taxonomy — unchanged) plus the priority it should run at. Deliberately carries
 * no dependency information itself — that lives in {@link CapabilityDependencies}, a separate
 * structure, so a step's identity/priority and the plan's dependency graph can be reasoned about
 * independently.
 */
public record CapabilityStep(CapabilityType type, CapabilityPriority priority) {
}
