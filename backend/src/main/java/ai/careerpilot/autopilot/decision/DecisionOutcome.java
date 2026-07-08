package ai.careerpilot.autopilot.decision;

/**
 * Phase 7.1 — the four terminal outcomes of the {@link ApplicationDecisionEngine}.
 *
 * <ul>
 *   <li>{@code AUTO_APPLY} — strong, safe match the agent may submit without a human (only ever
 *       reached when {@code application.auto.enabled} is also on and a provider supports the job).</li>
 *   <li>{@code HUMAN_REVIEW} — promising but needs a person: the safe default whenever any signal is
 *       missing, uncertain, or an external provider is unavailable.</li>
 *   <li>{@code SAVE} — worth keeping in the pipeline but not strong enough to act on automatically.</li>
 *   <li>{@code IGNORE} — below the pipeline threshold; not surfaced for action.</li>
 * </ul>
 */
public enum DecisionOutcome {
    AUTO_APPLY,
    HUMAN_REVIEW,
    SAVE,
    IGNORE
}
