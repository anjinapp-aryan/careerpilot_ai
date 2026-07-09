package ai.careerpilot.packageintel;

/**
 * Phase 7.11 — the verdict of {@link ApplicationPackageValidator}. Only {@code READY} is ever safe to
 * hand to Auto Apply; {@code HUMAN_REVIEW} and {@code BLOCKED} both stop automated submission.
 */
public enum PackageValidationStatus {
    /** Every applicable gate passed — the package is complete and internally consistent. */
    READY,
    /** Assembled but with a soft gap (e.g. not tailored, ATS missing) — a human should confirm. */
    HUMAN_REVIEW,
    /** A hard gate failed (no resume, no recommendation, mandatory field missing) — cannot apply. */
    BLOCKED
}
