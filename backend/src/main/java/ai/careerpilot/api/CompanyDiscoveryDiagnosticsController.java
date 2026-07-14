package ai.careerpilot.api;

import ai.careerpilot.jobdiscovery.discovery.CompanyDiscoveryHealthTracker;
import ai.careerpilot.jobdiscovery.discovery.CompanyDiscoveryService;
import ai.careerpilot.jobdiscovery.discovery.CompanySource;
import ai.careerpilot.repo.CompanyConnectorRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gap A — Company Discovery Agent diagnostics. No-auth counts-only surface, same convention as
 * {@link JobDiscoveryDiagnosticsController}, but additive/new — does not touch that controller's
 * existing {@code /enterprise} or {@code /providers} endpoints.
 */
@RestController
@RequestMapping("/api/diagnostics/company-discovery")
public class CompanyDiscoveryDiagnosticsController {

    private final CompanyDiscoveryHealthTracker health;
    private final List<CompanySource> sources;
    private final CompanyConnectorRepository repo;

    @Value("${company.discovery.enabled:false}") private boolean enabled;
    @Value("${company.discovery.cron:0 0 5 * * MON}") private String cron;

    public CompanyDiscoveryDiagnosticsController(CompanyDiscoveryHealthTracker health,
                                                  List<CompanySource> sources,
                                                  CompanyConnectorRepository repo) {
        this.health = health;
        this.sources = sources;
        this.repo = repo;
    }

    @GetMapping
    public Map<String, Object> summary() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schedulerEnabled", enabled);
        out.put("cron", cron);
        out.put("sourcesRegistered", sources.size());
        out.put("sourcesConfigured", sources.stream().filter(CompanySource::isConfigured).count());

        long pendingApproval = repo.countByDiscoveryStatus(CompanyDiscoveryService.PENDING_APPROVAL);
        long approved = repo.countByDiscoveryStatus(CompanyDiscoveryService.APPROVED);
        long rejected = repo.countByDiscoveryStatus(CompanyDiscoveryService.REJECTED);
        out.put("pendingApproval", pendingApproval);
        out.put("approved", approved);
        out.put("rejected", rejected);

        Map<String, Object> sourceHealth = new LinkedHashMap<>();
        long totalProbed = 0, totalHits = 0;
        for (CompanySource s : sources) {
            CompanyDiscoveryHealthTracker.Snapshot snap = health.snapshot(s.name());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("configured", s.isConfigured());
            m.put("circuitState", snap.circuitState());
            m.put("totalProbes", snap.totalProbes());
            m.put("hits", snap.hits());
            m.put("misses", snap.misses());
            m.put("failures", snap.failures());
            m.put("hitRate", snap.hitRate());
            m.put("lastLatencyMs", snap.lastLatencyMs());
            m.put("avgLatencyMs", snap.avgLatencyMs());
            m.put("lastRunAt", snap.lastRunAt());
            m.put("lastError", snap.lastError());
            sourceHealth.put(s.name(), m);
            totalProbed += snap.totalProbes();
            totalHits += snap.hits();
        }
        out.put("sources", sourceHealth);
        out.put("candidatesProbed", totalProbed);
        out.put("hits", totalHits);
        return out;
    }
}
