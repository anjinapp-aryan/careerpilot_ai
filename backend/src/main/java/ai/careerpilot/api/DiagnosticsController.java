package ai.careerpilot.api;

import ai.careerpilot.ai.AiGatewayService;
import ai.careerpilot.ai.AiGatewayProperties;
import ai.careerpilot.domain.ResumeTailoringJob;
import ai.careerpilot.repo.ResumeTailoringJobRepository;
import ai.careerpilot.service.profile.CandidateProfileMetrics;
import ai.careerpilot.jobdiscovery.enrich.JobAiEnrichmentMetrics;
import ai.careerpilot.jobdiscovery.cache.MatchCacheMetrics;
import ai.careerpilot.resumetailoring.cache.ResumeTailoringCacheMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Temporary diagnostics endpoint for AI Gateway troubleshooting.
 * Verifies API keys, models, connectivity, and routing.
 */
@RestController
@RequestMapping("/api/diagnostics")
public class DiagnosticsController {

    private static final Logger log = LoggerFactory.getLogger(DiagnosticsController.class);

    private final AiGatewayService gateway;
    private final AiGatewayProperties props;
    private final CandidateProfileMetrics candidateProfileMetrics;
    private final JobAiEnrichmentMetrics jobEnrichmentMetrics;
    private final MatchCacheMetrics matchCacheMetrics;

    @Value("${GEMINI_API_KEY:}")
    private String geminiKey;

    @Value("${DEEP_SHEEK_NVIDIA_API_KEY:}")
    private String deepSeekKey;

    @Value("${QWEN3_NVIDIA_API_KEY:}")
    private String qwenKey;

    @Value("${GROQ_API_KEY:}")
    private String groqKey;

    @Value("${candidate.profile.enabled:false}")
    private boolean candidateProfileEnabled;

    @Value("${jobs.enrich.ai.enabled:false}")
    private boolean jobEnrichmentEnabled;

    @Value("${jobs.matching.cache-enabled:false}")
    private boolean matchCacheEnabled;

    @Value("${resume.tailoring.enabled:false}")
    private boolean resumeTailoringEnabled;

    @Value("${resume.tailoring.preferred-providers:}")
    private List<String> resumeTailoringPreferredProviders;

    private final ResumeTailoringCacheMetrics resumeTailoringMetrics;
    private final ResumeTailoringJobRepository resumeTailoringJobs;
    private final ThreadPoolTaskExecutor resumeTailoringExecutor;

    public DiagnosticsController(AiGatewayService gateway, AiGatewayProperties props,
                                 CandidateProfileMetrics candidateProfileMetrics,
                                 JobAiEnrichmentMetrics jobEnrichmentMetrics,
                                 MatchCacheMetrics matchCacheMetrics,
                                 ResumeTailoringCacheMetrics resumeTailoringMetrics,
                                 ResumeTailoringJobRepository resumeTailoringJobs,
                                 ThreadPoolTaskExecutor resumeTailoringExecutor) {
        this.gateway = gateway;
        this.props = props;
        this.candidateProfileMetrics = candidateProfileMetrics;
        this.jobEnrichmentMetrics = jobEnrichmentMetrics;
        this.matchCacheMetrics = matchCacheMetrics;
        this.resumeTailoringMetrics = resumeTailoringMetrics;
        this.resumeTailoringJobs = resumeTailoringJobs;
        this.resumeTailoringExecutor = resumeTailoringExecutor;
    }

    @GetMapping("/ai")
    public Map<String, Object> aiDiagnostics() {
        Map<String, Object> result = new LinkedHashMap<>();

        // API Keys loaded
        Map<String, Object> keys = new LinkedHashMap<>();
        keys.put("gemini_loaded", !geminiKey.isBlank());
        keys.put("deepseek_loaded", !deepSeekKey.isBlank());
        keys.put("qwen_loaded", !qwenKey.isBlank());
        keys.put("groq_loaded", !groqKey.isBlank());
        result.put("api_keys", keys);

        // Models configured
        Map<String, String> models = new LinkedHashMap<>();
        props.getProviders().forEach((name, provider) -> {
            if (provider.getModel() != null) {
                models.put(name, provider.getModel());
            }
        });
        result.put("models", models);

        // Base URLs configured
        Map<String, String> baseUrls = new LinkedHashMap<>();
        props.getProviders().forEach((name, provider) -> {
            if (provider.getBaseUrl() != null) {
                baseUrls.put(name, provider.getBaseUrl());
            }
        });
        result.put("base_urls", baseUrls);

        // Gateway health
        result.put("gateway_health", gateway.health());

        // Gateway stats
        result.put("gateway_stats", gateway.stats());

        // Provider order
        result.put("provider_order", props.getOrder());
        result.put("primary_provider", props.getPrimary());

        // Default temperature
        result.put("default_temperature", props.getDefaultTemperature());

        log.info("AI Diagnostics endpoint accessed");
        return result;
    }

    /** Candidate Intelligence Profile generation metrics (counts/latency only — no PII). */
    @GetMapping("/candidate-profile")
    public Map<String, Object> candidateProfileDiagnostics() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", candidateProfileEnabled);
        result.putAll(candidateProfileMetrics.snapshot());
        log.info("Candidate Profile Diagnostics endpoint accessed");
        return result;
    }

    /** LLM job-enrichment metrics (counts/latency only — no posting content). */
    @GetMapping("/job-enrichment")
    public Map<String, Object> jobEnrichmentDiagnostics() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", jobEnrichmentEnabled);
        result.putAll(jobEnrichmentMetrics.snapshot());
        log.info("Job Enrichment Diagnostics endpoint accessed");
        return result;
    }

    /** Phase 2B-3 — MatchCache hit/miss counts (counts only, no cached content). */
    @GetMapping("/match-cache")
    public Map<String, Object> matchCacheDiagnostics() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", matchCacheEnabled);
        result.putAll(matchCacheMetrics.snapshot());
        log.info("Match Cache Diagnostics endpoint accessed");
        return result;
    }

    /** Phase 2D.1 — Resume Tailoring engine metrics (counts/latency only — no resume content). */
    @GetMapping("/resume-tailoring")
    public Map<String, Object> resumeTailoringDiagnostics() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", resumeTailoringEnabled);
        result.putAll(resumeTailoringMetrics.snapshot());
        log.info("Resume Tailoring Diagnostics endpoint accessed");
        return result;
    }

    /** Phase 2D.1.1 — live queue state: job counts by status + the bounded executor's own stats. */
    @GetMapping("/resume-tailoring/queue")
    public Map<String, Object> resumeTailoringQueueDiagnostics() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("queued", resumeTailoringJobs.countByStatus(ResumeTailoringJob.STATUS_QUEUED));
        result.put("running", resumeTailoringJobs.countByStatus(ResumeTailoringJob.STATUS_RUNNING));
        Instant since = Instant.now().minus(Duration.ofHours(24));
        result.put("succeededLast24h", resumeTailoringJobs.countByStatusAndCreatedAtAfter(ResumeTailoringJob.STATUS_SUCCEEDED, since));
        result.put("failedLast24h", resumeTailoringJobs.countByStatusAndCreatedAtAfter(ResumeTailoringJob.STATUS_FAILED, since));
        long oldestQueuedAgeSeconds = resumeTailoringJobs.findFirstByStatusOrderByCreatedAtAsc(ResumeTailoringJob.STATUS_QUEUED)
                .map(j -> Duration.between(j.getCreatedAt(), Instant.now()).getSeconds())
                .orElse(0L);
        result.put("oldestQueuedAgeSeconds", oldestQueuedAgeSeconds);
        result.put("executorActiveCount", resumeTailoringExecutor.getActiveCount());
        result.put("executorPoolSize", resumeTailoringExecutor.getPoolSize());
        result.put("executorQueueSize", resumeTailoringExecutor.getThreadPoolExecutor().getQueue().size());
        result.put("executorQueueCapacity", resumeTailoringExecutor.getQueueCapacity());
        log.info("Resume Tailoring Queue Diagnostics endpoint accessed");
        return result;
    }

    /**
     * Phase 2D.1.1 — computed UP/DEGRADED/DOWN verdict for the tailoring engine, mirroring
     * {@code AiGatewayService.health()}'s shape. DOWN when the executor queue is saturated or no
     * preferred provider is reachable; DEGRADED on an elevated recent failure rate; NOT_CONFIGURED
     * when the engine flag is off.
     */
    @GetMapping("/resume-tailoring/health")
    public Map<String, Object> resumeTailoringHealth() {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!resumeTailoringEnabled) {
            result.put("status", "NOT_CONFIGURED");
            return result;
        }

        int queueSize = resumeTailoringExecutor.getThreadPoolExecutor().getQueue().size();
        boolean queueSaturated = queueSize >= resumeTailoringExecutor.getQueueCapacity();

        List<String> preferred = resumeTailoringPreferredProviders == null || resumeTailoringPreferredProviders.isEmpty()
                ? props.getOrder() : resumeTailoringPreferredProviders;
        boolean anyPreferredProviderUp = gateway.providerStatuses().stream()
                .anyMatch(p -> preferred.contains(p.get("name")) && "UP".equals(p.get("status")));

        List<ResumeTailoringJob> recent = resumeTailoringJobs.findTop20ByOrderByCreatedAtDesc();
        long failed = recent.stream().filter(j -> ResumeTailoringJob.STATUS_FAILED.equals(j.getStatus())).count();
        double failureRate = recent.isEmpty() ? 0.0 : (double) failed / recent.size();

        String status;
        if (queueSaturated || !anyPreferredProviderUp) {
            status = "DOWN";
        } else if (failureRate > 0.30) {
            status = "DEGRADED";
        } else {
            status = "UP";
        }

        result.put("status", status);
        result.put("queueSaturated", queueSaturated);
        result.put("anyPreferredProviderUp", anyPreferredProviderUp);
        result.put("recentFailureRate", failureRate);
        result.put("recentSampleSize", recent.size());
        log.info("Resume Tailoring Health endpoint accessed — status={}", status);
        return result;
    }

    @GetMapping("/workflow")
    public Map<String, Object> workflowDiagnostics() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("workflowEngine", "UP");
        result.put("jsonSerialization", "UP");
        result.put("agentService", "UP");
        result.put("providers", gateway.health());
        log.info("Workflow Diagnostics endpoint accessed");
        return result;
    }
}
