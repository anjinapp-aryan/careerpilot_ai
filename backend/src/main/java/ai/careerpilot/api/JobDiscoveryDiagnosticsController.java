package ai.careerpilot.api;

import ai.careerpilot.jobdiscovery.JobAggregationService;
import ai.careerpilot.jobdiscovery.JobDiscoveryHealthTracker;
import ai.careerpilot.jobdiscovery.enterprise.CompanyConnector;
import ai.careerpilot.jobdiscovery.enterprise.CompanyConnectorService;
import ai.careerpilot.jobdiscovery.provider.EnterpriseAtsCompanyConnector;
import ai.careerpilot.jobdiscovery.provider.JobProvider;
import ai.careerpilot.jobdiscovery.provider.SuccessFactorsProvider;
import ai.careerpilot.jobdiscovery.provider.TaleoProvider;
import ai.careerpilot.jobdiscovery.provider.WorkdayCompanyConnector;
import ai.careerpilot.jobdiscovery.provider.WorkdayProvider;
import ai.careerpilot.repo.JobFetchAuditRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * No-auth counts-only diagnostics for the job-discovery provider layer (the 8-provider
 * Provider→Normalize→…→Persist pipeline owned by {@link JobAggregationService}), backed by
 * {@link JobDiscoveryHealthTracker}. Same convention as the other {@code /api/diagnostics/*}
 * controllers ({@code DiagnosticsController}, {@code DailyDiscoveryDiagnosticsController},
 * {@code PipelineDiagnosticsController}).
 *
 * <p>Deliberately namespaced under {@code /api/diagnostics/job-providers}, distinct from the
 * existing {@code /api/diagnostics/daily-discovery} (a separate, Phase 5Q agent covering a
 * different provider subset — Greenhouse/Lever/RemoteOK/Wellfound/CompanyCareerSites) and from
 * {@code /api/jobs/discovery/*} (the manual-trigger/audit endpoints on {@link
 * ai.careerpilot.api.JobController}) — no path collision with either.
 */
@RestController
@RequestMapping("/api/diagnostics/job-providers")
public class JobDiscoveryDiagnosticsController {

    private final JobDiscoveryHealthTracker health;
    private final List<JobProvider> providers;
    private final JobFetchAuditRepository audits;
    private final WorkdayProvider workday;
    private final TaleoProvider taleo;
    private final SuccessFactorsProvider successFactors;
    private final CompanyConnectorService connectors;

    @Value("${jobs.discovery.enabled:true}") private boolean schedulerEnabled;
    @Value("${jobs.discovery.hourly.enabled:false}") private boolean hourlyEnabled;
    @Value("${jobs.discovery.cron:0 0 6 * * *}") private String cron;
    @Value("${career.discovery.enterprise.enabled:false}") private boolean enterpriseEnabled;

    public JobDiscoveryDiagnosticsController(JobDiscoveryHealthTracker health,
                                             List<JobProvider> providers,
                                             JobFetchAuditRepository audits,
                                             WorkdayProvider workday,
                                             TaleoProvider taleo,
                                             SuccessFactorsProvider successFactors,
                                             CompanyConnectorService connectors) {
        this.health = health;
        this.providers = providers;
        this.audits = audits;
        this.workday = workday;
        this.taleo = taleo;
        this.successFactors = successFactors;
        this.connectors = connectors;
    }

    /**
     * Phase 5.3 — Enterprise ATS Connector Framework overview: master switch state, per-ATS
     * flag/company-connector detail, and the same health/circuit-breaker snapshot the generic
     * {@code /providers} endpoint already exposes (reused, not duplicated) for the three
     * Enterprise ATS provider names.
     */
    @GetMapping("/enterprise")
    public Map<String, Object> enterprise() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("masterEnabled", enterpriseEnabled);

        Map<String, Object> workdayOut = providerSummary(workday);
        workdayOut.put("flagEnabled", enterpriseEnabled);
        workdayOut.put("companyCount", workday.companies().size());
        workdayOut.put("companies", workday.companies().stream().map(this::workdayCompanySummary).toList());
        out.put("workday", workdayOut);

        Map<String, Object> taleoOut = providerSummary(taleo);
        taleoOut.put("flagEnabled", taleo.isFlagEnabled());
        taleoOut.put("companyCount", taleo.companies().size());
        taleoOut.put("companies", taleo.companies().stream().map(this::enterpriseCompanySummary).toList());
        taleoOut.put("note", "Connector architecture only — no verified public data feed; always inert.");
        out.put("taleo", taleoOut);

        Map<String, Object> sfOut = providerSummary(successFactors);
        sfOut.put("flagEnabled", successFactors.isFlagEnabled());
        sfOut.put("companyCount", successFactors.companies().size());
        sfOut.put("companies", successFactors.companies().stream().map(this::enterpriseCompanySummary).toList());
        sfOut.put("note", "Connector architecture only — no verified public data feed; always inert.");
        out.put("successfactors", sfOut);

        // Part 4/12 — persistent per-connector stats (jobs imported, health, failure count, avg
        // latency) live in CompanyConnectorService/CompanyConnector now, reused here rather than
        // re-derived; "jobs imported today"/"avg sync duration" reuse the same recent-audit
        // window `/discovery` above already reads, filtered to the 3 Enterprise ATS provider names.
        CompanyConnectorService.ConnectorStatistics stats = connectors.statistics();
        out.put("configuredCompanies", stats.total());
        out.put("healthyCompanies", stats.healthy());
        out.put("failedCompanies", stats.failed());
        out.put("disabledCompanies", stats.disabled());
        out.put("jobsImportedTotal", stats.jobsImportedTotal());
        out.put("averageJobsPerConnector", stats.averageJobsPerConnector());
        out.put("topPerformingConnector", stats.topPerformingConnector());
        out.put("worstConnector", stats.worstConnector());

        var enterpriseAudits = audits.findTop20ByOrderByStartedAtDesc().stream()
                .filter(a -> List.of("workday", "taleo", "successfactors").contains(a.getProvider()))
                .toList();
        Instant startOfToday = Instant.now().truncatedTo(ChronoUnit.DAYS);
        int jobsImportedToday = enterpriseAudits.stream()
                .filter(a -> a.getStartedAt() != null && !a.getStartedAt().isBefore(startOfToday))
                .mapToInt(a -> a.getJobsPersisted())
                .sum();
        double avgSyncDurationMs = enterpriseAudits.stream()
                .filter(a -> a.getStartedAt() != null && a.getFinishedAt() != null)
                .mapToLong(a -> Duration.between(a.getStartedAt(), a.getFinishedAt()).toMillis())
                .average().orElse(0);
        out.put("jobsImportedToday", jobsImportedToday);
        out.put("averageSyncDurationMs", avgSyncDurationMs);

        // Legacy field names kept for any existing caller of this endpoint (additive, not removed).
        long failedCompanies = List.of(workday).stream()
                .filter(p -> health.snapshot(p.name()).circuitState() == JobDiscoveryHealthTracker.CircuitState.OPEN)
                .count();
        out.put("failedConnectors", failedCompanies);
        out.put("disabledConnectors", (taleo.companies().isEmpty() ? 0 : 1) + (successFactors.companies().isEmpty() ? 0 : 1)
                + (workday.isConfigured() ? 0 : 1));
        return out;
    }

    private Map<String, Object> workdayCompanySummary(WorkdayCompanyConnector c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("company", c.companyName());
        m.put("atsType", "workday");
        m.put("careerUrl", c.careerUrl());
        m.put("publicJobEndpoint", c.jobsUrl());
        m.put("country", c.country());
        m.put("industry", c.industry());
        m.put("priority", c.priority());
        return m;
    }

    private Map<String, Object> enterpriseCompanySummary(EnterpriseAtsCompanyConnector c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("company", c.company());
        m.put("careerUrl", c.careerUrl());
        m.put("country", c.country());
        m.put("industry", c.industry());
        m.put("priority", c.priority());
        return m;
    }

    /** All registered providers' configured-state + health-tracker snapshot. */
    @GetMapping("/providers")
    public Map<String, Object> providers() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (JobProvider p : providers) {
            out.put(p.name(), providerSummary(p));
        }
        return out;
    }

    /** Single-provider detail; 404 when no provider with that name is registered. */
    @GetMapping("/providers/{provider}")
    public Map<String, Object> provider(@PathVariable String provider) {
        JobProvider match = providers.stream()
                .filter(p -> p.name().equalsIgnoreCase(provider))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "unknown provider: " + provider));
        return providerSummary(match);
    }

    /** Scheduler state (daily + hourly flags) and pool-wide fetch counts from the audit trail. */
    @GetMapping("/discovery")
    public Map<String, Object> discovery() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dailySchedulerEnabled", schedulerEnabled);
        out.put("hourlySchedulerEnabled", hourlyEnabled);
        out.put("cron", cron);
        out.put("providersRegistered", providers.size());
        out.put("providersConfigured", providers.stream().filter(JobProvider::isConfigured).count());

        var recent = audits.findTop20ByOrderByStartedAtDesc();
        int fetched = recent.stream().mapToInt(a -> a.getJobsFetched()).sum();
        int persisted = recent.stream().mapToInt(a -> a.getJobsPersisted()).sum();
        long failed = recent.stream().filter(a -> "FAILED".equals(a.getStatus())).count();
        Instant lastRun = recent.stream().map(a -> a.getStartedAt()).filter(java.util.Objects::nonNull)
                .max(Instant::compareTo).orElse(null);

        out.put("recentAuditRows", recent.size());
        out.put("recentJobsFetched", fetched);
        out.put("recentJobsImported", persisted);
        out.put("recentJobsRejected", fetched - persisted);
        out.put("recentFailedRuns", failed);
        out.put("lastRunAt", lastRun);
        return out;
    }

    private Map<String, Object> providerSummary(JobProvider p) {
        JobDiscoveryHealthTracker.Snapshot s = health.snapshot(p.name());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("provider", p.name());
        out.put("configured", p.isConfigured());
        out.put("circuitState", s.circuitState());
        out.put("totalRuns", s.totalRuns());
        out.put("successRuns", s.successRuns());
        out.put("failureRuns", s.failureRuns());
        out.put("successRate", s.successRate());
        out.put("lastLatencyMs", s.lastLatencyMs());
        out.put("avgLatencyMs", s.avgLatencyMs());
        out.put("lastJobsFetched", s.lastJobsFetched());
        out.put("lastJobsAccepted", s.lastJobsAccepted());
        out.put("lastJobsRejected", s.lastJobsRejected());
        out.put("lastRunAt", s.lastRunAt());
        out.put("lastError", s.lastError());
        out.put("health", !p.isConfigured() ? "NOT_CONFIGURED"
                : s.totalRuns() == 0 ? "NEVER_RUN"
                : s.circuitState() == JobDiscoveryHealthTracker.CircuitState.OPEN ? "DOWN"
                : s.circuitState() == JobDiscoveryHealthTracker.CircuitState.HALF_OPEN ? "DEGRADED"
                : "UP");
        return out;
    }
}
