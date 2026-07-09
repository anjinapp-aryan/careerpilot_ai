package ai.careerpilot.discovery.relevance;

/**
 * Phase 3B.1 — coarse career-relevance role buckets for the Domestic/International feed filter.
 * Deliberately narrower and separate from {@link ai.careerpilot.jobdiscovery.JobTaxonomy}'s
 * {@code ROLE_FAMILY} set (which drives recommendation scoring nuance) — this taxonomy exists only
 * to decide whether a job belongs in a career-aware feed at all, not to score it.
 */
public enum RoleFamily {
    JAVA_BACKEND,
    DATA_ENGINEERING,
    DEVOPS,
    FRONTEND,
    /** A job whose title/description matches a non-technical, always-irrelevant category. */
    EXCLUDED,
    /** Recognized as plausibly technical but not one of the named families above. */
    OTHER
}
