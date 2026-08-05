package ai.careerpilot.execution.verification.evidence;

/**
 * Phase 0 — how sure the platform is that an application actually reached the employer.
 *
 * <p>{@link #permitsSubmittedStatus()} is the single gate the whole phase exists to install: an
 * {@code ApplicationExecution} may only reach {@code STATUS_SUBMITTED} when this returns true.
 * Before this phase the terminal SUBMITTED transition happened unconditionally, with verification
 * best-effort inside a try/catch — so the platform could report a submission it could not prove.
 */
public enum ConfidenceLevel {

    /** Decisive evidence (an application id) corroborated by at least one other strong signal. */
    CONFIRMED,

    /** Either two or more independent strong signals, or one decisive signal on its own. */
    STRONG,

    /**
     * Exactly one strong signal, or corroborating evidence only. The submit click happened but the
     * outcome is unproven — reported honestly rather than as success, and never auto-retried
     * (retrying a possibly-successful submit is how duplicate applications get created).
     */
    WEAK,

    /** No positive evidence, or a detected error state. Treated as not submitted. */
    NONE;

    /** True only for {@link #CONFIRMED} and {@link #STRONG}. The gate. */
    public boolean permitsSubmittedStatus() {
        return this == CONFIRMED || this == STRONG;
    }
}
