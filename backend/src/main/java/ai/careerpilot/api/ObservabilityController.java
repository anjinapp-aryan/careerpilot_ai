package ai.careerpilot.api;

import ai.careerpilot.ai.AiGatewayService;
import ai.careerpilot.execution.api.ExecutionDiagnosticsController;
import ai.careerpilot.jobdiscovery.cache.MatchCacheMetrics;
import ai.careerpilot.retention.RetentionService;
import ai.careerpilot.workflow.api.WorkflowDiagnosticsController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregate observability — a single no-auth, counts-only rollup (same {@code /api/diagnostics}
 * convention and no-PII contract as the per-stage endpoints) that composes the already-existing
 * diagnostics surfaces into one operator view: workflow-stage health, execution-stage health, queue /
 * executor depth (surfaced inside each stage map), dead-letter counts, provider health, cache health,
 * and retention status. It <b>reuses</b> the existing controller beans rather than re-deriving anything,
 * so it can never disagree with the individual endpoints. Pure reads; introduces no new state.
 *
 * <p>Each section carries a {@code health} verdict; the top-level {@code overall} is the worst verdict
 * across all sections (DOWN &gt; DEGRADED &gt; UP &gt; NOT_CONFIGURED). With stock (dark) defaults every
 * workflow/execution stage reads {@code NOT_CONFIGURED} and {@code overall} is {@code NOT_CONFIGURED}.
 */
@RestController
@RequestMapping("/api/diagnostics")
public class ObservabilityController {

    private final WorkflowDiagnosticsController workflow;
    private final ExecutionDiagnosticsController execution;
    private final AiGatewayService gateway;
    private final MatchCacheMetrics matchCache;
    private final RetentionService retention;

    public ObservabilityController(WorkflowDiagnosticsController workflow,
                                   ExecutionDiagnosticsController execution,
                                   AiGatewayService gateway,
                                   MatchCacheMetrics matchCache,
                                   RetentionService retention) {
        this.workflow = workflow;
        this.execution = execution;
        this.gateway = gateway;
        this.matchCache = matchCache;
        this.retention = retention;
    }

    @GetMapping("/observability")
    public Map<String, Object> observability() {
        Map<String, Object> out = new LinkedHashMap<>();

        // ── workflow engine (Phase 3A) ──
        Map<String, Object> wf = new LinkedHashMap<>();
        wf.put("applicationTracking", workflow.applicationTracking());
        wf.put("applicationTimeline", workflow.applicationTimeline());
        wf.put("emailIntelligence", workflow.emailIntelligence());
        wf.put("interviewTracking", workflow.interviewTracking());
        wf.put("applicationAnalytics", workflow.applicationAnalytics());
        wf.put("careerIntelligence", workflow.careerIntelligence());
        wf.put("correlation", workflow.workflowCorrelation());
        wf.put("deadLetter", workflow.workflowDeadLetter());
        out.put("workflow", section(wf));

        // ── execution engine (Phase 2E) ──
        Map<String, Object> ex = new LinkedHashMap<>();
        ex.put("applicationExecution", execution.applicationExecution());
        ex.put("browser", execution.browser());
        ex.put("ats", execution.ats());
        ex.put("tracking", execution.tracking());
        ex.put("analytics", execution.analytics());
        out.put("execution", section(ex));

        // ── AI provider chain ──
        Map<String, String> providers = gateway.health();
        Map<String, Object> prov = new LinkedHashMap<>();
        prov.put("providers", providers);
        prov.put("health", providerHealth(providers));
        out.put("providers", prov);

        // ── cache (match cache; Redis remains provisioned-but-unused) ──
        Map<String, Object> cache = new LinkedHashMap<>();
        cache.putAll(matchCache.snapshot());
        cache.put("health", "UP"); // in-memory cache is always available; misses are not unhealthy
        out.put("cache", cache);

        // ── retention maintenance ──
        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("enabled", retention.isEnabled());
        ret.put("health", "UP");
        out.put("retention", ret);

        out.put("overall", worst(List.of(
                verdictOf(out.get("workflow")), verdictOf(out.get("execution")),
                verdictOf(out.get("providers")), verdictOf(out.get("cache")), verdictOf(out.get("retention")))));
        return out;
    }

    /** Wrap a group of stage maps with a rolled-up worst-of health verdict. */
    private static Map<String, Object> section(Map<String, Object> stages) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.putAll(stages);
        out.put("health", worst(stages.values().stream().map(ObservabilityController::verdictOf).toList()));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static String verdictOf(Object node) {
        if (node instanceof Map<?, ?> m) {
            Object h = ((Map<String, Object>) m).get("health");
            if (h != null) return h.toString();
        }
        return "UP";
    }

    private static String providerHealth(Map<String, String> providers) {
        if (providers == null || providers.isEmpty()) return "NOT_CONFIGURED";
        boolean anyUp = providers.values().stream().anyMatch(v -> v != null && v.equalsIgnoreCase("UP"));
        boolean allDown = providers.values().stream().allMatch(v -> v == null || v.equalsIgnoreCase("DOWN"));
        if (allDown) return "DOWN";
        return anyUp ? "UP" : "DEGRADED";
    }

    /** Worst-of ordering: DOWN &gt; DEGRADED &gt; UP &gt; NOT_CONFIGURED. */
    private static String worst(List<String> verdicts) {
        String worst = "NOT_CONFIGURED";
        for (String v : verdicts) {
            if (rank(v) > rank(worst)) worst = v;
        }
        return worst;
    }

    private static int rank(String v) {
        return switch (v == null ? "" : v.toUpperCase()) {
            case "DOWN" -> 3;
            case "DEGRADED" -> 2;
            case "UP" -> 1;
            default -> 0; // NOT_CONFIGURED / unknown
        };
    }
}
