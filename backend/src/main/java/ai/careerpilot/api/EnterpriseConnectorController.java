package ai.careerpilot.api;

import ai.careerpilot.jobdiscovery.enterprise.CompanyConnector;
import ai.careerpilot.jobdiscovery.enterprise.CompanyConnectorService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 5.3.1, Part 9 — additive-only admin API for the persistent Company Connector registry
 * ({@link CompanyConnectorService}). Same no-auth, counts/management convention as the other
 * {@code /api/diagnostics/*} and {@code /api/jobs/discovery/*} admin surfaces this codebase
 * already ships (no {@code @PreAuthorize} anywhere in the app — see CLAUDE.md) — this is new
 * surface area, not a change to any existing endpoint's contract.
 */
@RestController
@RequestMapping("/api/enterprise/connectors")
public class EnterpriseConnectorController {

    private final CompanyConnectorService service;

    public EnterpriseConnectorController(CompanyConnectorService service) {
        this.service = service;
    }

    @GetMapping
    public List<CompanyConnector> list(@RequestParam(required = false) String atsType) {
        return service.listConnectors(atsType);
    }

    @GetMapping("/{id}")
    public CompanyConnector get(@PathVariable UUID id) {
        return service.get(id).orElseThrow(() -> new ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "connector not found: " + id));
    }

    @PostMapping("/{id}/enable")
    public CompanyConnector enable(@PathVariable UUID id) {
        return service.setEnabled(id, true).orElseThrow(() -> new ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "connector not found: " + id));
    }

    @PostMapping("/{id}/disable")
    public CompanyConnector disable(@PathVariable UUID id) {
        return service.setEnabled(id, false).orElseThrow(() -> new ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "connector not found: " + id));
    }

    @PostMapping("/{id}/sync")
    public CompanyConnectorService.SyncResult sync(@PathVariable UUID id) {
        return service.syncConnector(id);
    }

    @PostMapping("/sync-all")
    public List<CompanyConnectorService.SyncResult> syncAll() {
        return service.syncAll();
    }

    @PostMapping("/sync-failed")
    public List<CompanyConnectorService.SyncResult> syncFailed() {
        return service.syncFailedConnectors();
    }

    @GetMapping("/statistics")
    public CompanyConnectorService.ConnectorStatistics statistics() {
        return service.statistics();
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        var stats = service.statistics();
        return Map.of(
                "total", stats.total(),
                "healthy", stats.healthy(),
                "failed", stats.failed(),
                "enabled", stats.enabled(),
                "disabled", stats.disabled());
    }
}
