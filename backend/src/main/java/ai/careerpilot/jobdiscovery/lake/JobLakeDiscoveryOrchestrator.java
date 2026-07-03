package ai.careerpilot.jobdiscovery.lake;

import ai.careerpilot.domain.JobDiscoveryAudit;
import ai.careerpilot.domain.JobLake;
import ai.careerpilot.domain.JobNormalized;
import ai.careerpilot.domain.JobRaw;
import ai.careerpilot.jobdiscovery.provider.JobProvider;
import ai.careerpilot.jobdiscovery.provider.RawJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Drives one Job Lake discovery run (Phase 2A Step 3): for each configured provider,
 * {@code fetch → job_raw → (software filter) → job_normalized → job_lake → (dedup) → audit}.
 * A failing provider is isolated — its error is recorded and the run continues — exactly like the
 * legacy {@code JobAggregationService}. This is the SHADOW pipeline: it never writes into {@code jobs}.
 *
 * <p><b>Flag-gated</b> by {@code JOB_DISCOVERY_ENABLED} (default false): a no-op until flipped on.
 * <b>Distributed-safe</b>: the whole run is bracketed by a Postgres advisory lock so two instances
 * (or a manual trigger overlapping the 02:00 scheduler) never double-process.
 * <b>Idempotent/resumable</b>: every layer upserts on its natural key, so a re-run after a crash
 * simply re-converges — no duplicate rows, no half-written state to clean up.
 */
@Service
public class JobLakeDiscoveryOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(JobLakeDiscoveryOrchestrator.class);

    /** Arbitrary, stable application lock key for {@code pg_try_advisory_lock}. */
    private static final long ADVISORY_LOCK_KEY = 0x6361726565724C41L; // "careerLA"

    /** Outcome of a run, surfaced to the manual-trigger endpoint and logs. */
    public record RunSummary(UUID runId, boolean ran, int providersRun, int found, int inserted, int duplicates) {
        static RunSummary skipped() { return new RunSummary(null, false, 0, 0, 0, 0); }
    }

    private final List<JobProvider> providers;
    private final JobRawService rawService;
    private final JobCategoryFilter categoryFilter;
    private final JobNormalizedService normalizedService;
    private final JobLakeService lakeService;
    private final JobLakeDeduplicationService dedup;
    private final JobDiscoveryAuditService auditService;
    private final JobLakeMetrics metrics;
    private final JdbcTemplate jdbc;
    private final boolean enabled;

    public JobLakeDiscoveryOrchestrator(List<JobProvider> providers,
                                        JobRawService rawService,
                                        JobCategoryFilter categoryFilter,
                                        JobNormalizedService normalizedService,
                                        JobLakeService lakeService,
                                        JobLakeDeduplicationService dedup,
                                        JobDiscoveryAuditService auditService,
                                        JobLakeMetrics metrics,
                                        JdbcTemplate jdbc,
                                        @Value("${jobs.discovery.lake.enabled:false}") boolean enabled) {
        this.providers = providers;
        this.rawService = rawService;
        this.categoryFilter = categoryFilter;
        this.normalizedService = normalizedService;
        this.lakeService = lakeService;
        this.dedup = dedup;
        this.auditService = auditService;
        this.metrics = metrics;
        this.jdbc = jdbc;
        this.enabled = enabled;
    }

    public boolean isEnabled() { return enabled; }

    /**
     * Run a discovery cycle under the advisory lock. Returns {@link RunSummary#skipped()} when the
     * flag is off or another run already holds the lock.
     */
    public RunSummary run() {
        if (!enabled) {
            log.debug("Lake discovery disabled (JOB_DISCOVERY_ENABLED=false); skipping");
            return RunSummary.skipped();
        }
        return jdbc.execute((ConnectionCallback<RunSummary>) conn -> {
            if (!tryAdvisoryLock(conn)) {
                log.info("Lake discovery: another run holds the advisory lock; skipping");
                return RunSummary.skipped();
            }
            try {
                return runLocked();
            } finally {
                advisoryUnlock(conn);
            }
        });
    }

    private RunSummary runLocked() {
        UUID runId = UUID.randomUUID();
        int providersRun = 0, totalFound = 0, totalInserted = 0, totalDuplicates = 0;
        log.info("Lake discovery run {} starting", runId);

        for (JobProvider provider : providers) {
            if (!provider.isConfigured()) continue;
            providersRun++;
            ProviderResult r = runProvider(runId, provider);
            totalFound += r.found;
            totalInserted += r.inserted;
            totalDuplicates += r.duplicates;
        }

        RunSummary summary = new RunSummary(runId, true, providersRun, totalFound, totalInserted, totalDuplicates);
        log.info("Lake discovery run {} complete: {}", runId, summary);
        return summary;
    }

    private record ProviderResult(int found, int inserted, int duplicates) {}

    private ProviderResult runProvider(UUID runId, JobProvider provider) {
        JobDiscoveryAudit audit = auditService.start(runId, provider.name());
        long t0 = System.nanoTime();
        int found = 0, inserted = 0, duplicates = 0, failures = 0;
        try {
            List<RawJob> raw = provider.fetch();
            for (RawJob r : raw) {
                if (r.externalId() == null || r.externalId().isBlank()) continue;
                found++;
                try {
                    // Step 4: never discard provider data — capture raw even for non-software jobs.
                    Optional<JobRaw> rawRow = rawService.capture(provider.name(), r, runId);
                    if (rawRow.isEmpty()) continue;

                    // Step 8: software-only gate BEFORE the normalized/lake layers (no-op when flag off).
                    if (!categoryFilter.isSoftwareJob(r.title(), r.description())) continue;

                    JobNormalized norm = normalizedService.upsert(r, provider.name(), rawRow.get().getId());
                    lakeService.upsert(norm.getId());                              // DISCOVERED
                    lakeService.advanceTo(norm.getId(), JobLake.STATUS_NORMALIZED);
                    inserted++;

                    if (dedup.isEnabled()) {
                        UUID canonical = dedup.deduplicate(norm);
                        lakeService.advanceTo(norm.getId(), JobLake.STATUS_DEDUPLICATED);
                        if (!canonical.equals(norm.getId())) duplicates++;
                        lakeService.setSourceCount(canonical, dedup.sourceCount(canonical));
                        lakeService.advanceTo(norm.getId(), JobLake.STATUS_READY_FOR_AI);
                    }
                } catch (Exception jobEx) {
                    // One bad job never aborts remaining jobs for this provider.
                    failures++;
                    log.warn("Lake discovery: provider {} job {} failed — skipping: {}",
                            provider.name(), r.externalId(), jobEx.toString());
                    auditService.recordFailure(runId, provider.name(), jobEx);
                }
            }
            metrics.recordProviderSuccess(provider.name(), found, inserted, duplicates, System.nanoTime() - t0);
        } catch (Exception e) {
            // Fetch-level failure: the whole provider call failed before we got any jobs.
            failures++;
            log.warn("Lake discovery: provider {} fetch failed: {}", provider.name(), e.toString());
            auditService.recordFailure(runId, provider.name(), e);
            metrics.recordProviderFailure(provider.name(), System.nanoTime() - t0);
        }
        auditService.complete(audit, found, inserted, duplicates, failures);
        return new ProviderResult(found, inserted, duplicates);
    }

    // ── Postgres advisory lock helpers (session-scoped on this borrowed connection) ──

    private boolean tryAdvisoryLock(java.sql.Connection conn) throws java.sql.SQLException {
        try (var ps = conn.prepareStatement("select pg_try_advisory_lock(?)")) {
            ps.setLong(1, ADVISORY_LOCK_KEY);
            try (var rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    private void advisoryUnlock(java.sql.Connection conn) {
        try (var ps = conn.prepareStatement("select pg_advisory_unlock(?)")) {
            ps.setLong(1, ADVISORY_LOCK_KEY);
            ps.execute();
        } catch (java.sql.SQLException e) {
            log.warn("Lake discovery: advisory unlock failed (lock auto-releases on connection close): {}", e.toString());
        }
    }
}
