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

import java.time.Instant;
import java.util.List;
import java.util.Map;

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

    public JobAggregationService(List<JobProvider> providers,
                                 JobNormalizer normalizer,
                                 JobRepository jobs,
                                 JobFetchAuditRepository audits,
                                 WorkflowEventProducer events) {
        this.providers = providers;
        this.normalizer = normalizer;
        this.jobs = jobs;
        this.audits = audits;
        this.events = events;
    }

    /** Result summary for the manual-trigger endpoint and logs. */
    public record DiscoverySummary(int providersRun, int totalFetched, int totalPersisted) {}

    public DiscoverySummary discoverAll() {
        int providersRun = 0, totalFetched = 0, totalPersisted = 0;
        for (JobProvider provider : providers) {
            if (!provider.isConfigured()) {
                log.debug("Skipping unconfigured provider {}", provider.name());
                continue;
            }
            providersRun++;
            DiscoverySummary one = runProvider(provider);
            totalFetched += one.totalFetched();
            totalPersisted += one.totalPersisted();
        }
        DiscoverySummary summary = new DiscoverySummary(providersRun, totalFetched, totalPersisted);
        events.publishJobEvent("job-discovery", "job.discovery.completed",
                Map.of("providersRun", providersRun, "fetched", totalFetched, "persisted", totalPersisted));
        log.info("Job discovery run complete: {}", summary);
        return summary;
    }

    private DiscoverySummary runProvider(JobProvider provider) {
        JobFetchAudit audit = audits.save(JobFetchAudit.builder()
                .provider(provider.name()).status("RUNNING").build());
        try {
            List<RawJob> raw = provider.fetch();
            int persisted = persist(provider.name(), raw);
            audit.setJobsFetched(raw.size());
            audit.setJobsPersisted(persisted);
            audit.setStatus("SUCCESS");
            audit.setFinishedAt(Instant.now());
            audits.save(audit);
            return new DiscoverySummary(1, raw.size(), persisted);
        } catch (Exception e) {
            log.warn("Provider {} fetch failed: {}", provider.name(), e.toString());
            audit.setStatus("FAILED");
            audit.setErrorMessage(truncate(e.toString(), 1000));
            audit.setFinishedAt(Instant.now());
            audits.save(audit);
            return new DiscoverySummary(1, 0, 0);
        }
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
