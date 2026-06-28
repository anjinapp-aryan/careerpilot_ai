package ai.careerpilot.api;

import ai.careerpilot.ai.AiGatewayService;
import ai.careerpilot.ai.AiGatewayProperties;
import ai.careerpilot.service.profile.CandidateProfileMetrics;
import ai.careerpilot.jobdiscovery.enrich.JobAiEnrichmentMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
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

    public DiagnosticsController(AiGatewayService gateway, AiGatewayProperties props,
                                 CandidateProfileMetrics candidateProfileMetrics,
                                 JobAiEnrichmentMetrics jobEnrichmentMetrics) {
        this.gateway = gateway;
        this.props = props;
        this.candidateProfileMetrics = candidateProfileMetrics;
        this.jobEnrichmentMetrics = jobEnrichmentMetrics;
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
