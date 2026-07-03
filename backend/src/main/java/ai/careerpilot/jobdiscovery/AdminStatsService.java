package ai.careerpilot.jobdiscovery;

import ai.careerpilot.api.dto.AdminStatsDtos.CountEntry;
import ai.careerpilot.api.dto.AdminStatsDtos.DiscoveryStats;
import ai.careerpilot.api.dto.AdminStatsDtos.DuplicateCluster;
import ai.careerpilot.api.dto.AdminStatsDtos.DuplicateStats;
import ai.careerpilot.api.dto.AdminStatsDtos.ProviderHealthEntry;
import ai.careerpilot.api.dto.AdminStatsDtos.SalaryBandEntry;
import ai.careerpilot.domain.JobFetchAudit;
import ai.careerpilot.jobdiscovery.enrich.JobAiEnrichmentMetrics;
import ai.careerpilot.repo.JobAiEnrichmentRepository;
import ai.careerpilot.repo.JobDuplicateRepository;
import ai.careerpilot.repo.JobFetchAuditRepository;
import ai.careerpilot.repo.JobFetchAuditRepository.ProviderRunStats;
import ai.careerpilot.repo.JobRepository;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only aggregations backing the Admin Dashboard (Phase 2 Increment D): discovery-provider
 * health, pool-size/coverage stats, the skill heatmap, and salary intelligence. Pure reads —
 * never writes, never touches matching/recommendations. All four panels are independent; a slow
 * or failing one never blocks the others (the controller calls each separately).
 */
@Service
public class AdminStatsService {

    private final JobRepository jobs;
    private final JobFetchAuditRepository audits;
    private final JobAiEnrichmentRepository enrichments;
    private final JobDuplicateRepository duplicates;
    private final JobAiEnrichmentMetrics enrichmentMetrics;

    public AdminStatsService(JobRepository jobs,
                             JobFetchAuditRepository audits,
                             JobAiEnrichmentRepository enrichments,
                             JobDuplicateRepository duplicates,
                             JobAiEnrichmentMetrics enrichmentMetrics) {
        this.jobs = jobs;
        this.audits = audits;
        this.enrichments = enrichments;
        this.duplicates = duplicates;
        this.enrichmentMetrics = enrichmentMetrics;
    }

    /** Latest status per discovery provider, joined with its 30-day success rate. */
    public List<ProviderHealthEntry> providerHealth() {
        Map<String, ProviderRunStats> rates = new HashMap<>();
        for (ProviderRunStats r : audits.successRateByProvider()) {
            rates.put(r.getProvider(), r);
        }
        return audits.findLatestPerProvider().stream()
                .map(latest -> toProviderHealthEntry(latest, rates.get(latest.getProvider())))
                .toList();
    }

    private static ProviderHealthEntry toProviderHealthEntry(JobFetchAudit latest, ProviderRunStats rate) {
        long success = rate != null ? rate.getSuccessCount() : 0;
        long total = rate != null ? rate.getTotalCount() : 0;
        double rate30d = total == 0 ? 0.0 : (success * 100.0) / total;
        return new ProviderHealthEntry(
                latest.getProvider(),
                latest.getStatus(),
                latest.getStartedAt(),
                latest.getJobsFetched(),
                latest.getJobsPersisted(),
                latest.getErrorMessage(),
                success,
                total,
                Math.round(rate30d * 10) / 10.0);
    }

    /** Pool size, embedding coverage, and country/source breakdowns. */
    public DiscoveryStats discoveryStats() {
        List<CountEntry> byCountry = jobs.countByCountry(10).stream()
                .map(r -> new CountEntry(r.getLabel(), r.getCnt()))
                .toList();
        List<CountEntry> bySource = jobs.countBySource().stream()
                .map(r -> new CountEntry(r.getLabel() == null ? "Unknown" : r.getLabel(), r.getCnt()))
                .toList();
        return new DiscoveryStats(jobs.countDiscovered(), jobs.countDiscoveredEmbedded(), byCountry, bySource);
    }

    /** Top N normalized skills across all LLM-enriched jobs. */
    public List<CountEntry> skillHeatmap(int limit) {
        return enrichments.skillHeatmap(Math.max(1, Math.min(limit, 50))).stream()
                .map(r -> new CountEntry(r.getSkill(), r.getCnt()))
                .toList();
    }

    /** Average salary band per (seniority, currency) pair, from LLM-enriched jobs. */
    public List<SalaryBandEntry> salaryIntelligence() {
        return enrichments.salaryBySeniority().stream()
                .map(r -> new SalaryBandEntry(r.getSeniorityLevel(), r.getSalaryCurrency(),
                        r.getAvgMin(), r.getAvgMax(), r.getCnt()))
                .toList();
    }

    /** Enrichment pipeline counters (attempts/success/failure/latency) — reuses the existing metrics bean. */
    public Map<String, Object> enrichmentMetrics() {
        return enrichmentMetrics.snapshot();
    }

    /** Duplicate-cluster summary (Phase 2 Increment C): how much clutter has been detected. */
    public DuplicateStats duplicateStats() {
        List<DuplicateCluster> top = duplicates.clusterSummary(10).stream()
                .map(c -> new DuplicateCluster(c.getCanonicalJobId(), c.getCanonicalTitle(),
                        c.getCanonicalCompany(), c.getMemberCount()))
                .toList();
        return new DuplicateStats(duplicates.countDuplicateGroups(), duplicates.countNonCanonicalDuplicates(), top);
    }
}
