package ai.careerpilot.dailydiscovery.api;

import ai.careerpilot.dailydiscovery.DailyJobDiscoveryCoordinator;
import ai.careerpilot.domain.DailyCareerSummary;
import ai.careerpilot.domain.DailyDiscoveryAnalytics;
import ai.careerpilot.repo.DailyCareerSummaryRepository;
import ai.careerpilot.repo.DailyDiscoveryAnalyticsRepository;
import ai.careerpilot.security.AuthenticatedUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 5 human REST surface — read-only summary/analytics for the signed-in user, plus a
 * manual-trigger endpoint (mirrors the existing {@code POST /api/jobs/discovery/run} convention).
 * Everything here degrades to empty/404 when the pipeline has never run (dark by default), never
 * fabricating a result.
 */
@RestController
@RequestMapping("/api/daily-discovery")
public class DailyDiscoveryController {

    private final DailyJobDiscoveryCoordinator coordinator;
    private final DailyCareerSummaryRepository summaries;
    private final DailyDiscoveryAnalyticsRepository analytics;

    @Value("${career.discovery.scheduler.enabled:false}") private boolean enabled;

    public DailyDiscoveryController(DailyJobDiscoveryCoordinator coordinator,
                                    DailyCareerSummaryRepository summaries,
                                    DailyDiscoveryAnalyticsRepository analytics) {
        this.coordinator = coordinator;
        this.summaries = summaries;
        this.analytics = analytics;
    }

    @GetMapping("/summary")
    public ResponseEntity<DailyCareerSummary> latestSummary(AuthenticatedUser user) {
        return summaries.findFirstByUserIdOrderByCreatedAtDesc(user.userId())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/analytics")
    public List<DailyDiscoveryAnalytics> analyticsHistory(AuthenticatedUser user) {
        return analytics.findByUserIdOrderByComputedAtDesc(user.userId());
    }

    @PostMapping("/run")
    public Map<String, Object> triggerManualRun(AuthenticatedUser user) {
        if (!enabled) {
            return Map.of("status", "NOT_ENABLED");
        }
        UUID runId = coordinator.runOnce();
        return Map.of("status", "STARTED", "runId", runId);
    }
}
