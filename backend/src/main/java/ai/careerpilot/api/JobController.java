package ai.careerpilot.api;

import ai.careerpilot.api.dto.JobMatchExplanationDto;
import ai.careerpilot.api.dto.JobRecommendationDtos.RecommendedJobsResponse;
import ai.careerpilot.api.dto.JobTelemetryEvent;
import ai.careerpilot.domain.Job;
import ai.careerpilot.domain.JobFetchAudit;
import ai.careerpilot.jobdiscovery.JobAggregationService;
import ai.careerpilot.jobdiscovery.JobAggregationService.DiscoverySummary;
import ai.careerpilot.repo.JobFetchAuditRepository;
import ai.careerpilot.security.AuthenticatedUser;
import ai.careerpilot.service.JobMatchExplanationService;
import ai.careerpilot.service.JobRecommendationService;
import ai.careerpilot.service.JobService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private static final Logger log = LoggerFactory.getLogger(JobController.class);

    private final JobService jobs;
    private final JobRecommendationService recommendations;
    private final JobAggregationService aggregation;
    private final JobFetchAuditRepository audits;
    private final JobMatchExplanationService explanations;

    public JobController(JobService jobs,
                         JobRecommendationService recommendations,
                         JobAggregationService aggregation,
                         JobFetchAuditRepository audits,
                         JobMatchExplanationService explanations) {
        this.jobs = jobs;
        this.recommendations = recommendations;
        this.aggregation = aggregation;
        this.audits = audits;
        this.explanations = explanations;
    }

    @GetMapping
    public Page<Job> search(AuthenticatedUser user,
                            @RequestParam(required = false) String q,
                            @RequestParam(defaultValue = "0") int page,
                            @RequestParam(defaultValue = "20") int size) {
        // Multi-tenant: filter by org
        return jobs.search(user.orgId(), q, page, size);
    }

    @PostMapping
    public Job create(AuthenticatedUser user, @RequestBody Job job) {
        return jobs.create(user.orgId(), job);
    }

    @GetMapping("/{id}")
    public Job get(AuthenticatedUser user, @PathVariable UUID id) {
        // Multi-tenant: verify ownership
        return jobs.get(user.orgId(), id);
    }

    /**
     * Recommended Jobs: deterministic 6-factor scoring, no AI call. Gated to high-confidence
     * matches (score >= threshold, confidence >= MEDIUM). {@code filter} drives the tab chips
     * (all|remote|hybrid|onsite|visa|relocation|high|new).
     */
    @GetMapping("/recommended")
    public RecommendedJobsResponse recommended(AuthenticatedUser user,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "10") int size,
                                               @RequestParam(defaultValue = "all") String filter,
                                               @RequestParam(required = false) Integer limit) {
        // `limit` kept for backward compatibility; when present it acts as the page size.
        int effectiveSize = limit != null ? limit : size;
        return recommendations.recommend(user.userId(), user.orgId(), page, effectiveSize, filter);
    }

    /**
     * Phase 2 Domestic/International tabs: read the global discovered-job pool.
     * {@code scope=domestic} returns jobs in {@code country}; anything else returns
     * everything outside it (incl. unknown-country jobs).
     */
    @GetMapping("/discovered")
    public Page<Job> discovered(AuthenticatedUser user,
                                @RequestParam(defaultValue = "international") String scope,
                                @RequestParam(defaultValue = "India") String country,
                                @RequestParam(required = false) String remoteType,
                                @RequestParam(required = false) Boolean sponsorship,
                                @RequestParam(required = false) Boolean relocation,
                                @RequestParam(required = false) String q,
                                @RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "20") int size) {
        return jobs.discovered(scope, country, remoteType, sponsorship, relocation, q, page, size);
    }

    /**
     * Browse "more opportunities": the global discovered pool minus this user's high-confidence
     * recommendations — i.e. the below-threshold jobs the spec requires to surface in Browse.
     */
    @GetMapping("/pool")
    public Page<Job> pool(AuthenticatedUser user,
                          @RequestParam(defaultValue = "0") int page,
                          @RequestParam(defaultValue = "20") int size) {
        return jobs.browsePool(user.userId(), page, size);
    }

    /**
     * "Why am I a match?" — the one LLM-backed job endpoint. Result is cached per (user, job),
     * so repeat clicks are free. Visible for global discovered jobs and the user's own org jobs.
     */
    @PostMapping("/{id}/explain")
    public JobMatchExplanationDto explain(AuthenticatedUser user, @PathVariable UUID id) {
        return explanations.explain(user.userId(), user.orgId(), id);
    }

    /**
     * UI interaction telemetry (filter usage, apply/save/why-match clicks). Fire-and-forget:
     * logged under JOB_TELEMETRY for observability, never blocks the UI.
     */
    @PostMapping("/telemetry")
    public void telemetry(AuthenticatedUser user, @RequestBody JobTelemetryEvent event) {
        log.info("JOB_TELEMETRY user={} event={} jobId={} filter={}",
                user.userId(), event.event(), event.jobId(), event.filter());
    }

    /**
     * Manually trigger a discovery run (the scheduler does this daily). Authenticated;
     * safe to call repeatedly — providers upsert and a failing provider is isolated.
     */
    @PostMapping("/discovery/run")
    public DiscoverySummary runDiscovery(AuthenticatedUser user) {
        return aggregation.discoverAll();
    }

    /** Recent per-provider fetch audit rows — observability for the ingest pipeline. */
    @GetMapping("/discovery/audit")
    public List<JobFetchAudit> discoveryAudit(AuthenticatedUser user) {
        return audits.findTop20ByOrderByStartedAtDesc();
    }
}
