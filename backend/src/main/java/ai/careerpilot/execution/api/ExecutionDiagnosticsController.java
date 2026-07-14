package ai.careerpilot.execution.api;

import ai.careerpilot.execution.analytics.AnalyticsMetrics;
import ai.careerpilot.execution.ats.ATSConnectorRegistry;
import ai.careerpilot.execution.ats.AtsConnectorMetrics;
import ai.careerpilot.execution.ats.GuestApplyEligibility;
import ai.careerpilot.execution.browser.BrowserAutomationMetrics;
import ai.careerpilot.execution.browser.GuestApplyAutomationService;
import ai.careerpilot.execution.config.ExecutionExecutorsConfig;
import ai.careerpilot.execution.execution.ApplicationExecutionMetrics;
import ai.careerpilot.execution.tracking.TrackingMetrics;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Phase 2E — one diagnostics endpoint per execution-pipeline stage, same no-auth counts-only
 * convention as {@code DiagnosticsController} / {@code PipelineDiagnosticsController} (no application
 * content, no PII). Each response carries: enabled flags, per-stage counters, live executor queue
 * depth, and a computed health verdict (NOT_CONFIGURED | UP | DEGRADED | DOWN). With stock defaults
 * every stage reads {@code NOT_CONFIGURED} — proving the whole engine registers without activating.
 */
@RestController
@RequestMapping("/api/diagnostics")
public class ExecutionDiagnosticsController {

    private final ApplicationExecutionMetrics executionMetrics;
    private final BrowserAutomationMetrics browserMetrics;
    private final AtsConnectorMetrics atsMetrics;
    private final TrackingMetrics trackingMetrics;
    private final AnalyticsMetrics analyticsMetrics;
    private final ATSConnectorRegistry atsRegistry;
    private final GuestApplyAutomationService guestApply;

    private final ThreadPoolTaskExecutor executionExecutor;
    private final ThreadPoolTaskExecutor browserExecutor;
    private final ThreadPoolTaskExecutor atsExecutor;
    private final ThreadPoolTaskExecutor trackingExecutor;
    private final ThreadPoolTaskExecutor analyticsExecutor;

    @Value("${application.execution.enabled:false}") private boolean executionEnabled;
    @Value("${application.execution.trigger.enabled:false}") private boolean executionTriggerEnabled;
    @Value("${browser.automation.enabled:false}") private boolean browserEnabled;
    @Value("${ats.connector.enabled:false}") private boolean atsEnabled;
    @Value("${application.tracking.enabled:false}") private boolean trackingEnabled;
    @Value("${application.tracking.trigger.enabled:false}") private boolean trackingTriggerEnabled;
    @Value("${application.analytics.enabled:false}") private boolean analyticsEnabled;
    @Value("${application.analytics.trigger.enabled:false}") private boolean analyticsTriggerEnabled;

    public ExecutionDiagnosticsController(
            ApplicationExecutionMetrics executionMetrics, BrowserAutomationMetrics browserMetrics,
            AtsConnectorMetrics atsMetrics, TrackingMetrics trackingMetrics,
            AnalyticsMetrics analyticsMetrics, ATSConnectorRegistry atsRegistry,
            GuestApplyAutomationService guestApply,
            @Qualifier(ExecutionExecutorsConfig.APPLICATION_EXECUTION_EXECUTOR) ThreadPoolTaskExecutor executionExecutor,
            @Qualifier(ExecutionExecutorsConfig.BROWSER_AUTOMATION_EXECUTOR) ThreadPoolTaskExecutor browserExecutor,
            @Qualifier(ExecutionExecutorsConfig.ATS_CONNECTOR_EXECUTOR) ThreadPoolTaskExecutor atsExecutor,
            @Qualifier(ExecutionExecutorsConfig.TRACKING_EXECUTOR) ThreadPoolTaskExecutor trackingExecutor,
            @Qualifier(ExecutionExecutorsConfig.ANALYTICS_EXECUTOR) ThreadPoolTaskExecutor analyticsExecutor) {
        this.executionMetrics = executionMetrics;
        this.browserMetrics = browserMetrics;
        this.atsMetrics = atsMetrics;
        this.trackingMetrics = trackingMetrics;
        this.analyticsMetrics = analyticsMetrics;
        this.atsRegistry = atsRegistry;
        this.guestApply = guestApply;
        this.executionExecutor = executionExecutor;
        this.browserExecutor = browserExecutor;
        this.atsExecutor = atsExecutor;
        this.trackingExecutor = trackingExecutor;
        this.analyticsExecutor = analyticsExecutor;
    }

    @GetMapping("/application-execution")
    public Map<String, Object> applicationExecution() {
        Map<String, Object> m = executionMetrics.snapshot();
        return stage(executionEnabled, executionTriggerEnabled, m, executionExecutor,
                (Long) m.get("applicationExecutionTotal"), (Long) m.get("applicationExecutionFailures"));
    }

    @GetMapping("/browser")
    public Map<String, Object> browser() {
        Map<String, Object> m = browserMetrics.snapshot();
        Map<String, Object> out = stage(browserEnabled, false, m, browserExecutor,
                (Long) m.get("browserTotal"), (Long) m.get("browserFailures"));
        // Gap D — diagnostics-visibility flag only; real enforcement is GuestApplyEligibility below.
        out.put("guestApplyOnlyFlag", guestApply.isGuestApplyOnlyFlagEnabled());
        out.put("guestApplyEligibleConnectors", java.util.List.of("greenhouse", "lever"));
        out.put("browserRealSubmissions", m.get("browserRealSubmissions"));
        out.put("browserSimulatedSubmissions", m.get("browserSimulatedSubmissions"));
        out.put("captchaOrLoginWallDetected", m.get("browserCaptchaOrLoginWallDetected"));
        out.put("formScreenshotApprovalsPending", m.get("browserFormScreenshotApprovalsPending"));
        return out;
    }

    @GetMapping("/ats")
    public Map<String, Object> ats() {
        Map<String, Object> m = atsMetrics.snapshot();
        Map<String, Object> out = stage(atsEnabled, false, m, atsExecutor,
                (Long) m.get("atsTotal"), (Long) m.get("atsFailures"));
        out.put("atsConnectorsRegistered", atsRegistry.all().size());
        out.put("atsConnectorsConfigured", atsRegistry.configuredCount());
        out.put("atsConnectorsGuestApplyEligible", atsRegistry.all().stream()
                .filter(c -> GuestApplyEligibility.isEligible(c.name()))
                .map(c -> c.name())
                .toList());
        return out;
    }

    @GetMapping("/tracking")
    public Map<String, Object> tracking() {
        Map<String, Object> m = trackingMetrics.snapshot();
        return stage(trackingEnabled, trackingTriggerEnabled, m, trackingExecutor,
                (Long) m.get("trackingTotal"), (Long) m.get("trackingFailures"));
    }

    @GetMapping("/analytics")
    public Map<String, Object> analytics() {
        Map<String, Object> m = analyticsMetrics.snapshot();
        return stage(analyticsEnabled, analyticsTriggerEnabled, m, analyticsExecutor,
                (Long) m.get("analyticsTotal"), (Long) m.get("analyticsFailures"));
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
