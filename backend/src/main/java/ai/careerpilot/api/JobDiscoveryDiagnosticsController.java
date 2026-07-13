package ai.careerpilot.api;

import ai.careerpilot.jobdiscovery.JobAggregationService;
import ai.careerpilot.jobdiscovery.JobDiscoveryHealthTracker;
import ai.careerpilot.jobdiscovery.provider.JobProvider;
import ai.careerpilot.repo.JobFetchAuditRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
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

    @Value("${jobs.discovery.enabled:true}") private boolean schedulerEnabled;
    @Value("${jobs.discovery.hourly.enabled:false}") private boolean hourlyEnabled;
    @Value("${jobs.discovery.cron:0 0 6 * * *}") private String cron;

    public JobDiscoveryDiagnosticsController(JobDiscoveryHealthTracker health,
                                             List<JobProvider> providers,
                                             JobFetchAuditRepository audits) {
        this.health = health;
        this.providers = providers;
        this.audits = audits;
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
