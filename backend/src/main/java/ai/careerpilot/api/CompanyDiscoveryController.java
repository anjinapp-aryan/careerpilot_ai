package ai.careerpilot.api;

import ai.careerpilot.jobdiscovery.discovery.CompanyDiscoveryService;
import ai.careerpilot.jobdiscovery.enterprise.CompanyConnector;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * Gap A — Company Discovery Agent admin API. Same no-auth, admin-management convention as {@link
 * EnterpriseConnectorController}/{@code /api/diagnostics/*} (no {@code @PreAuthorize} anywhere in
 * this app — see CLAUDE.md). Purely additive new surface area under {@code /api/company-discovery}
 * — does not modify any existing controller or endpoint contract.
 */
@RestController
@RequestMapping("/api/company-discovery")
public class CompanyDiscoveryController {

    private final CompanyDiscoveryService service;

    public CompanyDiscoveryController(CompanyDiscoveryService service) {
        this.service = service;
    }

    /** Discovered connectors currently awaiting admin review. */
    @GetMapping("/candidates")
    public List<CompanyConnector> candidates() {
        return service.pendingApproval();
    }

    @PostMapping("/{id}/approve")
    public CompanyConnector approve(@PathVariable UUID id) {
        return service.approve(id).orElseThrow(() -> new ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "discovered connector not found: " + id));
    }

    @PostMapping("/{id}/reject")
    public CompanyConnector reject(@PathVariable UUID id) {
        return service.reject(id).orElseThrow(() -> new ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "discovered connector not found: " + id));
    }

    /** Manual trigger — runs the same probe pass the scheduler runs weekly. */
    @PostMapping("/run")
    public CompanyDiscoveryService.DiscoveryRunResult run() {
        return service.discoverAll();
    }
}
