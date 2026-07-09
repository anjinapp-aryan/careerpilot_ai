package ai.careerpilot.dailydiscovery.api;

import ai.careerpilot.dailydiscovery.DailyJobDiscoveryMetrics;
import ai.careerpilot.jobdiscovery.provider.CompanyCareerSiteProvider;
import ai.careerpilot.jobdiscovery.provider.GreenhouseProvider;
import ai.careerpilot.jobdiscovery.provider.LeverProvider;
import ai.careerpilot.jobdiscovery.provider.WellfoundProvider;
import ai.careerpilot.repo.JobFetchAuditRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Phase 5Q — no-auth counts-only diagnostics for the daily discovery agent, same convention as
 * {@code PipelineDiagnosticsController}/{@code WorkflowDiagnosticsController}: enabled flags,
 * scheduler/run metrics, and per-provider health (reading {@code job_fetch_audit}, which
 * {@link ai.careerpilot.jobdiscovery.JobAggregationService} already writes for every provider —
 * no separate health table needed).
 */
@RestController
@RequestMapping("/api/diagnostics/daily-discovery")
public class DailyDiscoveryDiagnosticsController {

    private final DailyJobDiscoveryMetrics metrics;
    private final JobFetchAuditRepository audits;
    private final GreenhouseProvider greenhouse;
    private final LeverProvider lever;
    private final WellfoundProvider wellfound;
    private final CompanyCareerSiteProvider companySites;

    @Value("${career.discovery.scheduler.enabled:false}") private boolean schedulerEnabled;
    @Value("${career.discovery.analytics.enabled:false}") private boolean analyticsEnabled;
    @Value("${career.discovery.summary.enabled:false}") private boolean summaryEnabled;

    public DailyDiscoveryDiagnosticsController(DailyJobDiscoveryMetrics metrics,
                                               JobFetchAuditRepository audits,
                                               GreenhouseProvider greenhouse,
                                               LeverProvider lever,
                                               WellfoundProvider wellfound,
                                               CompanyCareerSiteProvider companySites) {
        this.metrics = metrics;
        this.audits = audits;
        this.greenhouse = greenhouse;
        this.lever = lever;
        this.wellfound = wellfound;
        this.companySites = companySites;
    }

    @GetMapping
    public Map<String, Object> scheduler() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schedulerEnabled", schedulerEnabled);
        out.put("analyticsEnabled", analyticsEnabled);
        out.put("summaryEnabled", summaryEnabled);
        out.putAll(metrics.snapshot());
        out.put("health", !schedulerEnabled ? "NOT_CONFIGURED"
                : "FAILED".equals(metrics.snapshot().get("lastStatus")) ? "DEGRADED" : "UP");
        return out;
    }

    @GetMapping("/providers")
    public Map<String, Object> providers() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("greenhouse", providerHealth("greenhouse", greenhouse.isConfigured()));
        out.put("lever", providerHealth("lever", lever.isConfigured()));
        out.put("remoteok", providerHealth("remoteok", true));
        out.put("wellfound", wellfoundStub(wellfound.isFlagEnabled()));
        out.put("companyCareerSites", companyStub(companySites.isFlagEnabled()));
        return out;
    }

    private Map<String, Object> providerHealth(String provider, boolean configured) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("configured", configured);
        Optional<ai.careerpilot.domain.JobFetchAudit> last = audits.findTopByProviderOrderByStartedAtDesc(provider);
        if (last.isEmpty()) {
            out.put("health", configured ? "NEVER_RUN" : "NOT_CONFIGURED");
            return out;
        }
        var a = last.get();
        out.put("lastStatus", a.getStatus());
        out.put("lastStartedAt", a.getStartedAt());
        out.put("lastFinishedAt", a.getFinishedAt());
        out.put("lastJobsFetched", a.getJobsFetched());
        out.put("lastJobsPersisted", a.getJobsPersisted());
        out.put("health", "SUCCESS".equals(a.getStatus()) ? "UP" : "DEGRADED");
        return out;
    }

    private Map<String, Object> wellfoundStub(boolean flagEnabled) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("configured", false);
        out.put("flagEnabled", flagEnabled);
        out.put("health", "NOT_CONFIGURED");
        out.put("reason", "No public Wellfound API; JS-rendered listings require a headless-browser crawler not implemented in this pass.");
        return out;
    }

    private Map<String, Object> companyStub(boolean flagEnabled) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("configured", false);
        out.put("flagEnabled", flagEnabled);
        out.put("targetCompanies", List.of(CompanyCareerSiteProvider.TARGET_COMPANIES));
        out.put("health", "NOT_CONFIGURED");
        out.put("reason", "Each site is a custom, JS-rendered ATS with no stable public JSON endpoint; not implemented in this pass.");
        return out;
    }
}
