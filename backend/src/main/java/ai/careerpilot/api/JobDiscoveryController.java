package ai.careerpilot.api;

import ai.careerpilot.domain.JobDiscoveryAudit;
import ai.careerpilot.domain.JobLake;
import ai.careerpilot.jobdiscovery.lake.JobDiscoveryAuditService;
import ai.careerpilot.jobdiscovery.lake.JobLakeDiscoveryOrchestrator;
import ai.careerpilot.jobdiscovery.lake.JobLakeDiscoveryOrchestrator.RunSummary;
import ai.careerpilot.jobdiscovery.lake.JobLakeMetrics;
import ai.careerpilot.jobdiscovery.provider.JobProvider;
import ai.careerpilot.repo.JobLakeRepository;
import ai.careerpilot.security.AuthenticatedUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 2A Job Lake API (Step 12). Deliberately mounted under {@code /api/jobs/discovery/lake/*} —
 * the legacy {@link JobController} already owns {@code /api/jobs/discovery/run} and
 * {@code /api/jobs/discovery/audit} for the {@code jobs}-table pipeline, so the lake gets its own
 * namespace to keep that contract byte-for-byte unchanged (ZERO API contract changes).
 *
 * <p>All read endpoints ({@code /status}, {@code /providers}, {@code /audit}) require any
 * authenticated user. The mutating {@code POST /run} endpoint is admin-gated (OWNER|ADMIN role)
 * because it triggers external HTTP calls to all configured providers and holds the Postgres
 * advisory lock for several minutes — any authenticated user triggering it would be a DoS vector.
 *
 * <p><b>Two-flag coupling:</b> {@code JOB_LAKE_DISCOVERY_ENABLED=true} alone never produces
 * {@code READY_FOR_AI} rows — jobs stop at {@code NORMALIZED}. To advance through
 * {@code DEDUPLICATED → READY_FOR_AI}, {@code JOB_DISCOVERY_DEDUP_ENABLED} must also be
 * {@code true}. This is by design: dedup can be enabled incrementally as a canary step
 * after raw/normalized volume is validated.
 */
@RestController
@RequestMapping("/api/jobs/discovery/lake")
public class JobDiscoveryController {

    private static final Logger log = LoggerFactory.getLogger(JobDiscoveryController.class);
    private static final Set<String> ADMIN_ROLES = Set.of("OWNER", "ADMIN");

    private final JobLakeDiscoveryOrchestrator orchestrator;
    private final JobDiscoveryAuditService auditService;
    private final JobLakeMetrics metrics;
    private final JobLakeRepository lake;
    private final List<JobProvider> providers;

    public JobDiscoveryController(JobLakeDiscoveryOrchestrator orchestrator,
                                  JobDiscoveryAuditService auditService,
                                  JobLakeMetrics metrics,
                                  JobLakeRepository lake,
                                  List<JobProvider> providers) {
        this.orchestrator = orchestrator;
        this.auditService = auditService;
        this.metrics = metrics;
        this.lake = lake;
        this.providers = providers;
    }

    /** GET /api/jobs/discovery/lake/status — flags, lake stage counts, and metrics snapshot. */
    @GetMapping("/status")
    public Map<String, Object> status(AuthenticatedUser user) {
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("discovered", lake.countByStatus(JobLake.STATUS_DISCOVERED));
        counts.put("normalized", lake.countByStatus(JobLake.STATUS_NORMALIZED));
        counts.put("deduplicated", lake.countByStatus(JobLake.STATUS_DEDUPLICATED));
        counts.put("readyForAi", lake.countByStatus(JobLake.STATUS_READY_FOR_AI));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", orchestrator.isEnabled());
        out.put("lakeCounts", counts);
        out.put("metrics", metrics.snapshot());
        return out;
    }

    /** GET /api/jobs/discovery/lake/providers — provider names and whether each is configured. */
    @GetMapping("/providers")
    public List<Map<String, Object>> providers(AuthenticatedUser user) {
        return providers.stream()
                .map(p -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", p.name());
                    m.put("configured", p.isConfigured());
                    return m;
                })
                .toList();
    }

    /** GET /api/jobs/discovery/lake/audit — recent run audit rows (optionally filtered by runId). */
    @GetMapping("/audit")
    public List<JobDiscoveryAudit> audit(AuthenticatedUser user,
                                         @RequestParam(required = false) UUID runId) {
        return runId != null ? auditService.auditsForRun(runId) : auditService.recentAudits();
    }

    /**
     * POST /api/jobs/discovery/lake/run — manually trigger a lake discovery run (the scheduler does
     * this daily at 02:00 UTC). Respects the master flag and the advisory lock: returns
     * {@code ran=false} when the flag is off or another run holds the lock.
     *
     * <p><b>Admin-only</b> (OWNER|ADMIN role). Any authenticated user triggering this would make
     * external HTTP calls to all providers and hold the advisory lock for minutes — a DoS vector.
     */
    @PostMapping("/run")
    public ResponseEntity<RunSummary> run(AuthenticatedUser user) {
        if (user == null || !ADMIN_ROLES.contains(user.role())) {
            log.warn("LAKE_RUN denied — user={} role={}", user != null ? user.userId() : null,
                    user != null ? user.role() : null);
            return ResponseEntity.status(403).build();
        }
        return ResponseEntity.ok(orchestrator.run());
    }
}
