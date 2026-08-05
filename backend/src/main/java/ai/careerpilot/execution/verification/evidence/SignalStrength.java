package ai.careerpilot.execution.verification.evidence;

/**
 * Phase 0 — how much weight one {@link VerificationSignal} carries in {@link
 * VerificationAdjudicator}.
 *
 * <p>{@link #CORROBORATING} exists specifically so that a screenshot can be attached as real,
 * human-auditable evidence without ever contributing to an automated success verdict. Before this
 * phase, the entire verification decision was "post-submit page is longer than 50 characters",
 * which is not evidence of anything and is deliberately unrepresentable here.
 */
public enum SignalStrength {

    /** Alone establishes that a submission occurred (today: only an extracted application id). */
    DECISIVE,

    /** Meaningful positive evidence, but any single one could in principle be produced by a non-success page. */
    STRONG,

    /** Supports a verdict for a human reader; contributes nothing to the automated confidence level. */
    CORROBORATING
}
