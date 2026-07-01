package ai.careerpilot.jobdiscovery.priority;

/**
 * Phase 2C-1 (Step 4) — the priority band a recommendation falls into, derived by
 * {@link PriorityEngine} from the match score plus additive attribute bonuses. Distinct from
 * {@link ai.careerpilot.jobdiscovery.JobCategory} (which is a pure score bucket): priority
 * deliberately over-weights actionable attributes (visa, remote, exact role) beyond their
 * scoring weight, as a separate ranking dimension.
 */
public enum PriorityLevel {
    CRITICAL,
    HIGH,
    MEDIUM,
    LOW
}
