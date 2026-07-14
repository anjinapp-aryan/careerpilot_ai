package ai.careerpilot.jobdiscovery;

import ai.careerpilot.domain.Job;
import ai.careerpilot.domain.JobFetchAudit;
import ai.careerpilot.jobdiscovery.provider.JobProvider;
import ai.careerpilot.jobdiscovery.provider.RawJob;
import ai.careerpilot.kafka.WorkflowEventProducer;
import ai.careerpilot.repo.JobFetchAuditRepository;
import ai.careerpilot.repo.JobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Orchestrates a discovery run: for each configured provider, fetch → normalize → upsert
 * into the global {@code jobs} pool (dedup on source+external_id), recording one
 * {@link JobFetchAudit} row per provider. A failing provider is isolated: it writes a
 * FAILED audit row and the run continues. Never throws to the scheduler/controller.
 */
@Service
public class JobAggregationService {

    private static final Logger log = LoggerFactory.getLogger(JobAggregationService.class);

    private final List<JobProvider> providers;
    private final JobNormalizer normalizer;
    private final JobRepository jobs;
    private final JobFetchAuditRepository audits;
    private final WorkflowEventProducer events;
    private final JobDiscoveryHealthTracker health;

    public JobAggregationService(List<JobProvider> providers,
                                 JobNormalizer normalizer,
                                 JobRepository jobs,
                                 JobFetchAuditRepository audits,
                                 WorkflowEventProducer events,
                                 JobDiscoveryHealthTracker health) {
        this.providers = providers;
        this.normalizer = normalizer;
        this.jobs = jobs;
        this.audits = audits;
        this.events = events;
        this.health = health;
    }

    /** Result summary for the manual-trigger endpoint and logs. */
    public record DiscoverySummary(int providersRun, int totalFetched, int totalPersisted) {}

    public DiscoverySummary discoverAll() {
        return discoverAll(null);
    }

    /**
     * Backfill variant: {@code window} (when non-null) restricts persisted jobs to those whose
     * {@code postedDate} falls within the window, e.g. {@code Duration.ofHours(24)} for a "last
     * 24h" backfill. This is a <b>client-side filter applied after fetch</b> — none of the
     * underlying source APIs (Greenhouse/Lever/Ashby/SmartRecruiters/RemoteOK/Arbeitnow/
     * Adzuna/Jooble) support server-side date-range queries, so a windowed backfill still fetches
     * each provider's full current listing and discards out-of-window rows locally. Jobs with no
     * {@code postedDate} are conservatively excluded from windowed runs (we can't verify they're
     * within the window), matching {@code jobsRejected} in the audit/health tracker.
     */
    public DiscoverySummary discoverAll(Duration window) {
        int providersRun = 0, totalFetched = 0, totalPersisted = 0;
        for (JobProvider provider : providers) {
            if (!provider.isConfigured()) {
                log.debug("Skipping unconfigured provider {}", provider.name());
                continue;
            }
            providersRun++;
            DiscoverySummary one = runProvider(provider, window);
            totalFetched += one.totalFetched();
            totalPersisted += one.totalPersisted();
        }
        DiscoverySummary summary = new DiscoverySummary(providersRun, totalFetched, totalPersisted);
        events.publishJobEvent("job-discovery", "job.discovery.completed",
                Map.of("providersRun", providersRun, "fetched", totalFetched, "persisted", totalPersisted));
        log.info("Job discovery run complete: {}", summary);
        return summary;
    }

    /**
     * Run exactly one named provider (case-insensitive match against {@link JobProvider#name()}).
     * Returns {@link Optional#empty()} when no provider with that name is registered. When the
     * matched provider is registered but not currently configured (e.g. missing API key/board
     * tokens), returns a zeroed {@link DiscoverySummary} rather than throwing — mirrors
     * {@link #discoverAll()}'s "skip unconfigured" behavior for a single-provider backfill.
     */
    public Optional<DiscoverySummary> discoverProvider(String providerName) {
        return discoverProvider(providerName, null);
    }

    /** Single-provider backfill with an optional time window — see {@link #discoverAll(Duration)}. */
    public Optional<DiscoverySummary> discoverProvider(String providerName, Duration window) {
        JobProvider match = providers.stream()
                .filter(p -> p.name().equalsIgnoreCase(providerName))
                .findFirst()
                .orElse(null);
        if (match == null) return Optional.empty();
        if (!match.isConfigured()) {
            log.debug("discoverProvider: {} is not configured; nothing to run", providerName);
            return Optional.of(new DiscoverySummary(0, 0, 0));
        }
        return Optional.of(runProvider(match, window));
    }

    private DiscoverySummary runProvider(JobProvider provider, Duration window) {
        JobFetchAudit audit = audits.save(JobFetchAudit.builder()
                .provider(provider.name()).status("RUNNING").build());
        long startedAt = System.currentTimeMillis();
        try {
            List<RawJob> raw = provider.fetch();
            List<RawJob> toPersist = window == null ? raw : filterByWindow(raw, window);
            int persisted = persist(provider.name(), toPersist);
            audit.setJobsFetched(raw.size());
            audit.setJobsPersisted(persisted);
            audit.setStatus("SUCCESS");
            audit.setFinishedAt(Instant.now());
            audits.save(audit);
            health.recordSuccess(provider.name(), System.currentTimeMillis() - startedAt,
                    raw.size(), persisted, raw.size() - persisted);
            return new DiscoverySummary(1, raw.size(), persisted);
        } catch (Exception e) {
            log.warn("Provider {} fetch failed: {}", provider.name(), e.toString());
            audit.setStatus("FAILED");
            audit.setErrorMessage(truncate(e.toString(), 1000));
            audit.setFinishedAt(Instant.now());
            audits.save(audit);
            health.recordFailure(provider.name(), System.currentTimeMillis() - startedAt, e.toString());
            return new DiscoverySummary(1, 0, 0);
        }
    }

    /** Client-side postedDate filter for windowed backfills — see {@link #discoverAll(Duration)}. */
    static List<RawJob> filterByWindow(List<RawJob> raw, Duration window) {
        Instant cutoff = Instant.now().minus(window);
        return raw.stream()
                .filter(r -> r.postedDate() != null && !r.postedDate().isBefore(cutoff))
                .toList();
    }

    /**
     * Phase 5.3.1 — public entry point for callers that fetch outside the standard
     * {@link #discoverAll()}/{@link #discoverProvider(String)} loop (e.g.
     * {@code CompanyConnectorService}'s single-connector manual sync) but still want the exact
     * same normalize/dedupe/upsert logic — reuses {@link #persist(String, List)} rather than
     * duplicating it.
     */
    public int persistRawJobs(String source, List<RawJob> raw) {
        return persist(source, raw);
    }

    /** Upsert each raw job; existing rows (same source+external_id) are refreshed in place. */
    @Transactional
    protected int persist(String source, List<RawJob> raw) {
        int count = 0;
        for (RawJob r : raw) {
            if (r.externalId() == null || r.externalId().isBlank()) continue;
            Job fresh = normalizer.toJob(r, source);
            jobs.findBySourceAndExternalId(source, r.externalId())
                    .ifPresentOrElse(
                            existing -> { normalizer.merge(existing, fresh); jobs.save(existing); },
                            () -> jobs.save(fresh));
            count++;
        }
        return count;
    }

    private static String truncate(String s, int max) {
        return (s == null || s.length() <= max) ? s : s.substring(0, max);
    }
}
