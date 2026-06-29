package ai.careerpilot.api;

import ai.careerpilot.api.dto.JobMatchExplanationDto;
import ai.careerpilot.api.dto.JobRecommendationDtos.RecommendedJobsResponse;
import ai.careerpilot.api.dto.JobTelemetryEvent;
import ai.careerpilot.domain.Job;
import ai.careerpilot.domain.JobFetchAudit;
import ai.careerpilot.jobdiscovery.JobAggregationService;
import ai.careerpilot.jobdiscovery.JobAggregationService.DiscoverySummary;
import ai.careerpilot.jobdiscovery.JobEmbeddingService;
import ai.careerpilot.jobdiscovery.enrich.JobAiEnrichmentService;
import ai.careerpilot.jobdiscovery.dedup.JobDuplicateDetectionService;
import ai.careerpilot.domain.JobAiEnrichment;
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
import java.util.Map;
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
    private final JobEmbeddingService embeddings;
    private final JobAiEnrichmentService enrichment;
    private final JobDuplicateDetectionService dedup;

    public JobController(JobService jobs,
                         JobRecommendationService recommendations,
                         JobAggregationService aggregation,
                         JobFetchAuditRepository audits,
                         JobMatchExplanationService explanations,
                         JobEmbeddingService embeddings,
                         JobAiEnrichmentService enrichment,
                         JobDuplicateDetectionService dedup) {
        this.jobs = jobs;
        this.recommendations = recommendations;
        this.aggregation = aggregation;
        this.audits = audits;
        this.explanations = explanations;
        this.embeddings = embeddings;
        this.enrichment = enrichment;
        this.dedup = dedup;
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
     * Domestic/International tabs: read the global discovered-job pool. The {@code scope} selects the
     * country set, which (when strict scope is enabled) is derived server-side from the authenticated
     * candidate's profile — the {@code country} param is legacy-only and ignored in strict mode, so
     * Domestic can never be widened by the client.
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
        return jobs.discovered(user.userId(), scope, country, remoteType, sponsorship, relocation, q, page, size);
    }

    /**
     * Semantic search over the embedded discovered pool (Phase 2 Increment A): embeds {@code q} and
     * returns the cosine nearest neighbors. Returns an empty list when embeddings are disabled or
     * nothing is embedded yet — callers should fall back to the keyword {@code /discovered} search.
     */
    @GetMapping("/search/semantic")
    public List<Job> semanticSearch(AuthenticatedUser user,
                                    @RequestParam String q,
                                    @RequestParam(defaultValue = "20") int k) {
        return embeddings.semanticSearch(q, k);
    }

    /**
     * Manually embed discovered jobs that have no embedding yet (capped per call). Idempotent and
     * safe to re-run; a no-op when embeddings are disabled. Returns the number embedded.
     */
    @PostMapping("/embeddings/backfill")
    public Map<String, Integer> backfillEmbeddings(AuthenticatedUser user,
                                                   @RequestParam(required = false) Integer limit) {
        int written = limit != null ? embeddings.embedMissingJobs(limit) : embeddings.embedMissingJobs();
        return Map.of("embedded", written);
    }

    /**
     * LLM-enrich discovered jobs that have no enrichment row yet (capped per call). Idempotent and
     * safe to re-run; a no-op when enrichment is disabled. Returns the number enriched. The "on-demand
     * batch" half of Increment B (the scheduler runs this nightly).
     */
    @PostMapping("/enrich/backfill")
    public Map<String, Integer> backfillEnrichment(AuthenticatedUser user,
                                                   @RequestParam(required = false) Integer limit) {
        int written = limit != null ? enrichment.enrichMissingJobs(limit) : enrichment.enrichMissingJobs();
        return Map.of("enriched", written);
    }

    /**
     * On-demand single-job enrichment: enrich (or re-enrich) one job synchronously. Returns the saved
     * enrichment, or 404 when enrichment is disabled, the job is unknown, or extraction failed.
     */
    @PostMapping("/{id}/enrich")
    public JobAiEnrichment enrichOne(AuthenticatedUser user, @PathVariable UUID id) {
        return enrichment.enrichOne(id)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND,
                        "enrichment unavailable (disabled, unknown job, or extraction failed)"));
    }

    /**
     * Read-only lookup for the Jobs UI's "AI Insights" expander (Phase 2 Increment D) — returns the
     * already-stored enrichment row, no LLM call. 404 when the job hasn't been enriched yet (the UI
     * should treat that as "no insights available", not an error).
     */
    @GetMapping("/{id}/enrichment")
    public JobAiEnrichment getEnrichment(AuthenticatedUser user, @PathVariable UUID id) {
        return enrichment.getEnrichment(id)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "no enrichment available for this job"));
    }

    /**
     * Detect cross-source duplicates among embedded jobs that haven't been duplicate-checked yet
     * (capped per call). Idempotent and safe to re-run; a no-op when dedup is disabled. Returns the
     * number of jobs checked (not the number found duplicate — see GET /api/admin/stats/duplicates
     * for cluster counts). The "on-demand batch" half of Increment C (the scheduler runs this nightly).
     */
    @PostMapping("/dedup/backfill")
    public Map<String, Integer> backfillDedup(AuthenticatedUser user,
                                              @RequestParam(required = false) Integer limit) {
        int checked = limit != null ? dedup.detectDuplicates(limit) : dedup.detectDuplicates();
        return Map.of("checked", checked);
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
