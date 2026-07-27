package ai.careerpilot.planner;

/**
 * Phase 11.2 — priority band for a {@link CapabilityStep} within a {@link CapabilityPlan}. Same
 * naming as the existing {@code JobRecommendation.priority} string convention (CRITICAL/HIGH/
 * MEDIUM/LOW), kept as a proper enum here since this is new code, not a value stored in an
 * existing column.
 */
public enum CapabilityPriority {
    CRITICAL,
    HIGH,
    MEDIUM,
    LOW
}
