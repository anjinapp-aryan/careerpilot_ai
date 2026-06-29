package ai.careerpilot.api;

import ai.careerpilot.api.dto.AdminStatsDtos.CountEntry;
import ai.careerpilot.api.dto.AdminStatsDtos.DiscoveryStats;
import ai.careerpilot.api.dto.AdminStatsDtos.DuplicateStats;
import ai.careerpilot.api.dto.AdminStatsDtos.ProviderHealthEntry;
import ai.careerpilot.api.dto.AdminStatsDtos.SalaryBandEntry;
import ai.careerpilot.jobdiscovery.AdminStatsService;
import ai.careerpilot.security.AuthenticatedUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Admin Dashboard read endpoints (Phase 2 Increment D): discovery-provider health, pool stats,
 * skill heatmap, and salary intelligence — all derived from data Increments A/B already produce.
 * Pure reads; never touches matching/recommendations.
 *
 * <p>Same manual admin-role gate as {@link AdminCandidateProfileController} (the project has
 * {@code @EnableMethodSecurity} on but uses no {@code @PreAuthorize} anywhere): non-admin → 403.
 * No feature flag here — these are read-only aggregations over data that's already either present
 * or empty, so there's nothing unsafe to gate behind a flag.
 */
@RestController
@RequestMapping("/api/admin/stats")
public class AdminStatsController {

    private static final Logger log = LoggerFactory.getLogger(AdminStatsController.class);
    private static final Set<String> ADMIN_ROLES = Set.of("OWNER", "ADMIN");

    private final AdminStatsService stats;

    public AdminStatsController(AdminStatsService stats) {
        this.stats = stats;
    }

    @GetMapping("/provider-health")
    public ResponseEntity<List<ProviderHealthEntry>> providerHealth(AuthenticatedUser user) {
        return guarded(user, stats::providerHealth);
    }

    @GetMapping("/discovery")
    public ResponseEntity<DiscoveryStats> discoveryStats(AuthenticatedUser user) {
        return guarded(user, stats::discoveryStats);
    }

    @GetMapping("/skill-heatmap")
    public ResponseEntity<List<CountEntry>> skillHeatmap(AuthenticatedUser user,
                                                         @RequestParam(defaultValue = "20") int limit) {
        return guarded(user, () -> stats.skillHeatmap(limit));
    }

    @GetMapping("/salary-intelligence")
    public ResponseEntity<List<SalaryBandEntry>> salaryIntelligence(AuthenticatedUser user) {
        return guarded(user, stats::salaryIntelligence);
    }

    @GetMapping("/enrichment-metrics")
    public ResponseEntity<Map<String, Object>> enrichmentMetrics(AuthenticatedUser user) {
        return guarded(user, stats::enrichmentMetrics);
    }

    /** Duplicate-cluster summary (Phase 2 Increment C). */
    @GetMapping("/duplicates")
    public ResponseEntity<DuplicateStats> duplicateStats(AuthenticatedUser user) {
        return guarded(user, stats::duplicateStats);
    }

    private <T> ResponseEntity<T> guarded(AuthenticatedUser user, java.util.function.Supplier<T> body) {
        if (user == null || !ADMIN_ROLES.contains(user.role())) {
            log.warn("ADMIN_STATS denied — user={} role={}", user != null ? user.userId() : null,
                    user != null ? user.role() : null);
            return ResponseEntity.status(403).build();
        }
        return ResponseEntity.ok(body.get());
    }
}
