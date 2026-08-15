package ai.careerpilot.jobdiscovery;

/**
 * International Job Discovery Phase 2 — deterministic industry classification for a job posting.
 * Never inferred from country: a German job is not BANKING merely because it's German — see
 * {@link IndustryFitClassifier}.
 */
public enum IndustryFit {
    BANKING,
    FINTECH,
    ENTERPRISE,
    CLOUD,
    PLATFORM,
    /** No reliable signal — never guessed from country, company size, or anything else. */
    UNKNOWN
}
