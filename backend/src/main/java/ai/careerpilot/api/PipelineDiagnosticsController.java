package ai.careerpilot.api;

import ai.careerpilot.resumetailoring.apppackage.ApplicationPackageMetrics;
import ai.careerpilot.resumetailoring.autoapply.AutoApplyPreparationMetrics;
import ai.careerpilot.resumetailoring.config.PipelineExecutorsConfig;
import ai.careerpilot.resumetailoring.coverletter.CoverLetterMetrics;
import ai.careerpilot.resumetailoring.explain.AtsExplainabilityMetrics;
import ai.careerpilot.resumetailoring.gap.GapAnalysisMetrics;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Phase 2D.3–2D.7 — one diagnostics endpoint per pipeline stage, same no-auth counts-only
 * convention as {@code DiagnosticsController} (no resume/letter content, no PII). Each response
 * carries: enabled flags, throughput/latency/cache/provider counters (from the stage's Metrics
 * component), live executor queue depth, and a computed health verdict
 * (NOT_CONFIGURED | UP | DEGRADED | DOWN).
 */
@RestController
@RequestMapping("/api/diagnostics")
public class PipelineDiagnosticsController {

    private final GapAnalysisMetrics gapMetrics;
    private final AtsExplainabilityMetrics explainMetrics;
    private final CoverLetterMetrics coverLetterMetrics;
    private final ApplicationPackageMetrics packageMetrics;
    private final AutoApplyPreparationMetrics autoApplyMetrics;

    private final ThreadPoolTaskExecutor gapExecutor;
    private final ThreadPoolTaskExecutor explainExecutor;
    private final ThreadPoolTaskExecutor coverLetterExecutor;
    private final ThreadPoolTaskExecutor packageExecutor;
    private final ThreadPoolTaskExecutor autoApplyExecutor;

    @Value("${gap.analysis.enabled:false}") private boolean gapEnabled;
    @Value("${gap.analysis.trigger.enabled:false}") private boolean gapTriggerEnabled;
    @Value("${ats.explainability.enabled:false}") private boolean explainEnabled;
    @Value("${ats.explainability.trigger.enabled:false}") private boolean explainTriggerEnabled;
    @Value("${cover.letter.enabled:false}") private boolean coverLetterEnabled;
    @Value("${cover.letter.trigger.enabled:false}") private boolean coverLetterTriggerEnabled;
    @Value("${application.package.enabled:false}") private boolean packageEnabled;
    @Value("${application.package.trigger.enabled:false}") private boolean packageTriggerEnabled;
    @Value("${auto.apply.package.enabled:false}") private boolean autoApplyEnabled;
    @Value("${auto.apply.trigger.enabled:false}") private boolean autoApplyTriggerEnabled;

    public PipelineDiagnosticsController(
            GapAnalysisMetrics gapMetrics, AtsExplainabilityMetrics explainMetrics,
            CoverLetterMetrics coverLetterMetrics, ApplicationPackageMetrics packageMetrics,
            AutoApplyPreparationMetrics autoApplyMetrics,
            @Qualifier(PipelineExecutorsConfig.GAP_ANALYSIS_EXECUTOR) ThreadPoolTaskExecutor gapExecutor,
            @Qualifier(PipelineExecutorsConfig.ATS_EXPLAINABILITY_EXECUTOR) ThreadPoolTaskExecutor explainExecutor,
            @Qualifier(PipelineExecutorsConfig.COVER_LETTER_EXECUTOR) ThreadPoolTaskExecutor coverLetterExecutor,
            @Qualifier(PipelineExecutorsConfig.APPLICATION_PACKAGE_EXECUTOR) ThreadPoolTaskExecutor packageExecutor,
            @Qualifier(PipelineExecutorsConfig.AUTO_APPLY_PREPARATION_EXECUTOR) ThreadPoolTaskExecutor autoApplyExecutor) {
        this.gapMetrics = gapMetrics;
        this.explainMetrics = explainMetrics;
        this.coverLetterMetrics = coverLetterMetrics;
        this.packageMetrics = packageMetrics;
        this.autoApplyMetrics = autoApplyMetrics;
        this.gapExecutor = gapExecutor;
        this.explainExecutor = explainExecutor;
        this.coverLetterExecutor = coverLetterExecutor;
        this.packageExecutor = packageExecutor;
        this.autoApplyExecutor = autoApplyExecutor;
    }

    @GetMapping("/gap-analysis")
    public Map<String, Object> gapAnalysis() {
        return stage(gapEnabled, gapTriggerEnabled, gapMetrics.snapshot(), gapExecutor,
                (Long) gapMetrics.snapshot().get("gapAnalysisTotal"),
                (Long) gapMetrics.snapshot().get("gapAnalysisFailures"));
    }

    @GetMapping("/ats-explainability")
    public Map<String, Object> atsExplainability() {
        return stage(explainEnabled, explainTriggerEnabled, explainMetrics.snapshot(), explainExecutor,
                (Long) explainMetrics.snapshot().get("atsExplainabilityTotal"),
                (Long) explainMetrics.snapshot().get("atsExplainabilityFailures"));
    }

    @GetMapping("/cover-letter")
    public Map<String, Object> coverLetter() {
        return stage(coverLetterEnabled, coverLetterTriggerEnabled, coverLetterMetrics.snapshot(), coverLetterExecutor,
                (Long) coverLetterMetrics.snapshot().get("coverLetterTotal"),
                (Long) coverLetterMetrics.snapshot().get("coverLetterFailures"));
    }

    @GetMapping("/application-package")
    public Map<String, Object> applicationPackage() {
        return stage(packageEnabled, packageTriggerEnabled, packageMetrics.snapshot(), packageExecutor,
                (Long) packageMetrics.snapshot().get("applicationPackageTotal"),
                (Long) packageMetrics.snapshot().get("applicationPackageFailures"));
    }

    @GetMapping("/auto-apply-package")
    public Map<String, Object> autoApplyPackage() {
        return stage(autoApplyEnabled, autoApplyTriggerEnabled, autoApplyMetrics.snapshot(), autoApplyExecutor,
                (Long) autoApplyMetrics.snapshot().get("autoApplyTotal"),
                (Long) autoApplyMetrics.snapshot().get("autoApplyFailures"));
    }

    private static Map<String, Object> stage(boolean enabled, boolean triggerEnabled,
                                             Map<String, Object> metrics, ThreadPoolTaskExecutor executor,
                                             Long total, Long failures) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", enabled);
        out.put("triggerEnabled", triggerEnabled);
        out.putAll(metrics);
        int queueSize = executor.getThreadPoolExecutor().getQueue().size();
        out.put("executorActiveCount", executor.getActiveCount());
        out.put("executorPoolSize", executor.getPoolSize());
        out.put("executorQueueSize", queueSize);
        out.put("executorQueueCapacity", executor.getQueueCapacity());

        String health;
        if (!enabled) {
            health = "NOT_CONFIGURED";
        } else if (queueSize >= executor.getQueueCapacity()) {
            health = "DOWN";
        } else if (total != null && total > 0 && failures != null && failures * 100 > total * 30) {
            health = "DEGRADED";
        } else {
            health = "UP";
        }
        out.put("health", health);
        return out;
    }
}
