package ai.careerpilot.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Response shapes for the Admin Dashboard (Phase 2 Increment D). Plain records, never raw
 * entities — mirrors the project's DTO convention (see {@code WorkflowDtos}).
 */
public final class AdminStatsDtos {

    private AdminStatsDtos() {}

    /** A single label→count bucket, used for country/source breakdowns and the skill heatmap. */
    public record CountEntry(String label, long count) {}

    /** Health of one discovery provider (RemoteOK/Arbeitnow/Adzuna/Jooble) — distinct from AI-provider health. */
    public record ProviderHealthEntry(
            String provider,
            String lastStatus,
            Instant lastRunAt,
            int lastJobsFetched,
            int lastJobsPersisted,
            String lastErrorMessage,
            long successCount30d,
            long totalRuns30d,
            double successRatePercent) {}

    /** Snapshot of the discovered-job pool: size, embedding coverage, and breakdowns. */
    public record DiscoveryStats(
            long totalDiscovered,
            long totalEmbedded,
            List<CountEntry> byCountry,
            List<CountEntry> bySource) {}

    /** Average salary band for one (seniority, currency) pair — currencies are never averaged together. */
    public record SalaryBandEntry(
            String seniorityLevel,
            String currency,
            BigDecimal avgMin,
            BigDecimal avgMax,
            long count) {}

    /** One detected duplicate cluster (Phase 2 Increment C) — the canonical posting plus how many members. */
    public record DuplicateCluster(
            java.util.UUID canonicalJobId,
            String canonicalTitle,
            String canonicalCompany,
            long memberCount) {}

    /** Pool-wide duplicate-detection summary. */
    public record DuplicateStats(
            long totalGroups,
            long totalDuplicateJobs,
            List<DuplicateCluster> topClusters) {}
}
