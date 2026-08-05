package ai.careerpilot.careertimeline;

/**
 * Phase 10H — the categories the Career Timeline aggregator actually sources from real,
 * already-persisted data. Deliberately does NOT include an "ANALYTICS" category: the closest
 * backend data ({@code ApplicationAnalytics}/{@code CareerIntelligence}) is a raw numeric
 * snapshot series with no discrete "personal best" / "score improved" event ever computed or
 * persisted — inventing one here would be exactly the kind of fabricated narrative this feature
 * must never produce. See {@link CareerTimelineService}'s class javadoc for the full rationale.
 */
public enum CareerTimelineCategory {
    MISSION,
    APPLICATION,
    RESUME,
    LEARNING,
    INTERVIEW,
    MEMORY,
    COMPANY,
    WORKFLOW
}
