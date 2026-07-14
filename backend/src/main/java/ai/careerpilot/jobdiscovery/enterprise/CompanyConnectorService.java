package ai.careerpilot.jobdiscovery.enterprise;

import ai.careerpilot.jobdiscovery.JobAggregationService;
import ai.careerpilot.jobdiscovery.provider.RawJob;
import ai.careerpilot.jobdiscovery.provider.SuccessFactorsProvider;
import ai.careerpilot.jobdiscovery.provider.TaleoProvider;
import ai.careerpilot.jobdiscovery.provider.WorkdayCompanyConnector;
import ai.careerpilot.jobdiscovery.provider.WorkdayProvider;
import ai.careerpilot.repo.CompanyConnectorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 5.3.1 — Part 2: admin-manageable layer on top of the Phase 5.3 CSV-configured Enterprise
 * ATS connectors. Never changes {@link ai.careerpilot.jobdiscovery.provider.JobProvider}: this
 * service owns its own persistence ({@link CompanyConnectorRepository}) and drives
 * {@link WorkdayProvider#fetchOne}/{@link JobAggregationService#persistRawJobs} directly for
 * per-connector manual sync, reusing the exact same HTTP/mapping/normalize/dedupe/upsert logic
 * the scheduled {@code JobAggregationService.discoverAll()} run already uses — no parallel
 * pipeline.
 *
 * <p><b>Bootstrap, not replacement:</b> {@code career.discovery.{workday,taleo,successfactors}
 * .companies} CSV env vars remain the deploy-time source of truth and keep working completely
 * unchanged (Workday/Taleo/SuccessFactors providers still read them directly, exactly as in
 * Phase 5.3). The first time {@link #listConnectors(String)} (or any other read) is called and
 * this table is empty, it is seeded from that same CSV-parsed configuration — after that, the
 * DB row (not the CSV) is authoritative for {@code enabled}/health/stats, since only the DB can
 * represent admin actions (enable/disable/manual sync) that the CSV cannot express.
 */
@Service
public class CompanyConnectorService {

    private static final Logger log = LoggerFactory.getLogger(CompanyConnectorService.class);

    public static final String WORKDAY = "WORKDAY";
    public static final String TALEO = "TALEO";
    public static final String SUCCESSFACTORS = "SUCCESSFACTORS";

    private final CompanyConnectorRepository repo;
    private final WorkdayProvider workday;
    private final TaleoProvider taleo;
    private final SuccessFactorsProvider successFactors;
    private final JobAggregationService aggregation;

    public CompanyConnectorService(CompanyConnectorRepository repo,
                                    WorkdayProvider workday,
                                    TaleoProvider taleo,
                                    SuccessFactorsProvider successFactors,
                                    JobAggregationService aggregation) {
        this.repo = repo;
        this.workday = workday;
        this.taleo = taleo;
        this.successFactors = successFactors;
        this.aggregation = aggregation;
    }

    // ---- Part 2: load / enable / disable / update ----------------------------------------

    @Transactional
    public List<CompanyConnector> listConnectors(String atsTypeOrNull) {
        bootstrapIfEmpty();
        return atsTypeOrNull == null || atsTypeOrNull.isBlank()
                ? repo.findAll()
                : repo.findByAtsTypeOrderByPriorityDesc(atsTypeOrNull.toUpperCase());
    }

    public Optional<CompanyConnector> get(UUID id) {
        return repo.findById(id);
    }

    @Transactional
    public Optional<CompanyConnector> setEnabled(UUID id, boolean enabled) {
        return repo.findById(id).map(c -> { c.setEnabled(enabled); return repo.save(c); });
    }

    @Transactional
    public Optional<CompanyConnector> update(UUID id, String careerUrl, String country, String industry, Integer priority) {
        return repo.findById(id).map(c -> {
            if (careerUrl != null) c.setCareerUrl(careerUrl);
            if (country != null) c.setCountry(country);
            if (industry != null) c.setIndustry(industry);
            if (priority != null) c.setPriority(priority);
            return repo.save(c);
        });
    }

    // ---- Part 8: connector sync (reuses JobAggregationService.persistRawJobs) ------------

    public record SyncResult(UUID connectorId, String company, String atsType, boolean success,
                              int jobsFetched, int jobsImported, long latencyMs, String error) {}

    /** Sync exactly one connector. Taleo/SuccessFactors always return a "not configured" no-op result (they are inert — see their provider javadocs). */
    @Transactional
    public SyncResult syncConnector(UUID id) {
        CompanyConnector c = repo.findById(id).orElseThrow(() -> new java.util.NoSuchElementException("connector not found: " + id));
        if (Boolean.FALSE.equals(c.getEnabled())) {
            return new SyncResult(id, c.getCompanyName(), c.getAtsType(), false, 0, 0, 0, "connector is disabled");
        }
        long start = System.currentTimeMillis();
        try {
            List<RawJob> raw = fetchForConnector(c);
            long latency = System.currentTimeMillis() - start;
            int imported = raw.isEmpty() ? 0 : aggregation.persistRawJobs(providerSourceName(c.getAtsType()), raw);
            recordSuccess(c, raw.size(), imported, latency);
            return new SyncResult(id, c.getCompanyName(), c.getAtsType(), true, raw.size(), imported, latency, null);
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            recordFailure(c, latency, e.toString());
            log.warn("company-connector sync failed id={} company={} ats={}: {}", id, c.getCompanyName(), c.getAtsType(), e.toString());
            return new SyncResult(id, c.getCompanyName(), c.getAtsType(), false, 0, 0, latency, e.toString());
        }
    }

    /** Sync every enabled connector, sequentially per-ATS-type (each type keeps its own existing throttle-between-calls behavior). */
    public List<SyncResult> syncAll() {
        return repo.findByEnabledTrue().stream().map(c -> syncConnector(c.getId())).toList();
    }

    /** Re-sync only connectors currently unhealthy (DOWN or NEVER_RUN with a recorded failure). */
    public List<SyncResult> syncFailedConnectors() {
        return repo.findByEnabledTrue().stream()
                .filter(c -> "DOWN".equals(c.getHealthStatus()) || (c.getFailureCount() != null && c.getFailureCount() > 0))
                .map(c -> syncConnector(c.getId()))
                .toList();
    }

    private List<RawJob> fetchForConnector(CompanyConnector c) {
        if (WORKDAY.equals(c.getAtsType())) {
            var wc = new WorkdayCompanyConnector(c.getTenant(), c.getCluster(), c.getSite(),
                    c.getCompanyName(), c.getCountry(), c.getIndustry(), c.getPriority() == null ? 0 : c.getPriority());
            return workday.fetchOne(wc);
        }
        // Taleo/SuccessFactors are connector-architecture-only (see their provider class javadocs) —
        // no real fetch to make; syncing one just confirms/records that inert state honestly.
        return List.of();
    }

    private static String providerSourceName(String atsType) {
        return switch (atsType) {
            case WORKDAY -> "workday";
            case TALEO -> "taleo";
            case SUCCESSFACTORS -> "successfactors";
            default -> atsType.toLowerCase();
        };
    }

    private void recordSuccess(CompanyConnector c, int fetched, int imported, long latencyMs) {
        c.setLastSuccessfulSync(Instant.now());
        c.setFailureCount(0);
        c.setJobsImported((c.getJobsImported() == null ? 0 : c.getJobsImported()) + imported);
        c.setAverageLatencyMs(averageLatency(c.getAverageLatencyMs(), latencyMs));
        c.setHealthStatus(WORKDAY.equals(c.getAtsType()) ? "UP" : "NOT_CONFIGURED");
        repo.save(c);
    }

    private void recordFailure(CompanyConnector c, long latencyMs, String reason) {
        c.setLastFailure(Instant.now());
        c.setLastFailureReason(reason);
        c.setFailureCount((c.getFailureCount() == null ? 0 : c.getFailureCount()) + 1);
        c.setAverageLatencyMs(averageLatency(c.getAverageLatencyMs(), latencyMs));
        c.setHealthStatus(c.getFailureCount() >= 3 ? "DOWN" : "DEGRADED");
        repo.save(c);
    }

    private static long averageLatency(Long previousAvg, long latest) {
        long prev = previousAvg == null ? 0 : previousAvg;
        return prev == 0 ? latest : Math.round(prev * 0.7 + latest * 0.3);
    }

    // ---- Part 4/12: statistics -------------------------------------------------------------

    public record ConnectorStatistics(long total, long enabled, long disabled, long healthy, long failed,
                                       long jobsImportedTotal, double averageJobsPerConnector,
                                       String topPerformingConnector, String worstConnector) {}

    @Transactional
    public ConnectorStatistics statistics() {
        bootstrapIfEmpty();
        List<CompanyConnector> all = repo.findAll();
        long total = all.size();
        long enabled = all.stream().filter(c -> Boolean.TRUE.equals(c.getEnabled())).count();
        long healthy = all.stream().filter(c -> "UP".equals(c.getHealthStatus())).count();
        long failed = all.stream().filter(c -> "DOWN".equals(c.getHealthStatus())).count();
        long jobsTotal = all.stream().mapToLong(c -> c.getJobsImported() == null ? 0 : c.getJobsImported()).sum();
        double avgJobs = total == 0 ? 0 : (double) jobsTotal / total;
        String top = all.stream().max(java.util.Comparator.comparingInt(c -> c.getJobsImported() == null ? 0 : c.getJobsImported()))
                .map(CompanyConnector::getCompanyName).orElse(null);
        String worst = all.stream().filter(c -> c.getFailureCount() != null && c.getFailureCount() > 0)
                .max(java.util.Comparator.comparingInt(CompanyConnector::getFailureCount))
                .map(CompanyConnector::getCompanyName).orElse(null);
        return new ConnectorStatistics(total, enabled, total - enabled, healthy, failed, jobsTotal, avgJobs, top, worst);
    }

    // ---- bootstrap -------------------------------------------------------------------------

    @Transactional
    void bootstrapIfEmpty() {
        if (repo.count() > 0) return;
        List<CompanyConnector> seed = new java.util.ArrayList<>();
        for (WorkdayCompanyConnector w : workday.companies()) {
            seed.add(CompanyConnector.builder()
                    .companyName(w.companyName()).atsType(WORKDAY)
                    .careerUrl(w.careerUrl()).tenant(w.tenant()).cluster(w.cluster()).site(w.site())
                    .country(w.country()).industry(w.industry()).priority(w.priority())
                    .enabled(true).healthStatus("NEVER_RUN").build());
        }
        for (var t : taleo.companies()) {
            seed.add(CompanyConnector.builder()
                    .companyName(t.company()).atsType(TALEO).careerUrl(t.careerUrl())
                    .country(t.country()).industry(t.industry()).priority(t.priority())
                    .enabled(true).healthStatus("NOT_CONFIGURED").build());
        }
        for (var s : successFactors.companies()) {
            seed.add(CompanyConnector.builder()
                    .companyName(s.company()).atsType(SUCCESSFACTORS).careerUrl(s.careerUrl())
                    .country(s.country()).industry(s.industry()).priority(s.priority())
                    .enabled(true).healthStatus("NOT_CONFIGURED").build());
        }
        if (!seed.isEmpty()) {
            repo.saveAll(seed);
            log.info("company-connectors: bootstrapped {} rows from CSV configuration", seed.size());
        }
    }
}
