package ai.careerpilot.execution.api;

import ai.careerpilot.execution.analytics.AnalyticsMetrics;
import ai.careerpilot.execution.ats.ATSConnectorRegistry;
import ai.careerpilot.execution.ats.AtsConnectorMetrics;
import ai.careerpilot.execution.ats.GuestApplyEligibility;
import ai.careerpilot.execution.browser.BrowserAutomationMetrics;
import ai.careerpilot.execution.browser.GuestApplyAutomationService;
import ai.careerpilot.execution.config.ExecutionExecutorsConfig;
import ai.careerpilot.execution.execution.ApplicationExecutionMetrics;
import ai.careerpilot.execution.operations.OperationsService;
import ai.careerpilot.execution.recovery.RecoveryMetrics;
import ai.careerpilot.execution.retry.RetryMetrics;
import ai.careerpilot.execution.tracking.TrackingMetrics;
import ai.careerpilot.execution.verification.VerificationMetrics;
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
    private final VerificationMetrics verificationMetrics;
    private final RetryMetrics retryMetrics;
    private final RecoveryMetrics recoveryMetrics;
    private final ATSConnectorRegistry atsRegistry;
    private final GuestApplyAutomationService guestApply;
    private final OperationsService operations;
    private final ai.careerpilot.execution.browser.pool.BrowserLeasePool leasePool;
    private final ai.careerpilot.execution.browser.pool.BrowserPoolMetrics poolMetrics;
    private final ai.careerpilot.execution.browser.pool.BrowserLaunchOptionsFactory launchOptions;
    private final ai.careerpilot.execution.browser.BrowserHealthService browserHealth;

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
    @Value("${application.recovery.enabled:false}") private boolean recoveryEnabled;
    @Value("${application.recovery.trigger.enabled:false}") private boolean recoveryTriggerEnabled;
    @Value("${application.operations.enabled:false}") private boolean operationsEnabled;

    public ExecutionDiagnosticsController(
            ApplicationExecutionMetrics executionMetrics, BrowserAutomationMetrics browserMetrics,
            AtsConnectorMetrics atsMetrics, TrackingMetrics trackingMetrics,
            AnalyticsMetrics analyticsMetrics, VerificationMetrics verificationMetrics,
            RetryMetrics retryMetrics, RecoveryMetrics recoveryMetrics,
            ATSConnectorRegistry atsRegistry,
            GuestApplyAutomationService guestApply,
            OperationsService operations,
            ai.careerpilot.execution.browser.pool.BrowserLeasePool leasePool,
            ai.careerpilot.execution.browser.pool.BrowserPoolMetrics poolMetrics,
            ai.careerpilot.execution.browser.pool.BrowserLaunchOptionsFactory launchOptions,
            ai.careerpilot.execution.browser.BrowserHealthService browserHealth,
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
        this.verificationMetrics = verificationMetrics;
        this.retryMetrics = retryMetrics;
        this.recoveryMetrics = recoveryMetrics;
        this.atsRegistry = atsRegistry;
        this.guestApply = guestApply;
        this.operations = operations;
        this.leasePool = leasePool;
        this.poolMetrics = poolMetrics;
        this.launchOptions = launchOptions;
        this.browserHealth = browserHealth;
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

    /**
     * Phase 12B — the browser stage's report is now assembled by {@link
     * ai.careerpilot.execution.browser.BrowserHealthService} rather than inline here, because it
     * grew past what a controller method should own (runtime/ARM derivation, installation probe,
     * launch lifecycle, rollout stage, capacity, outcomes, and a composite verdict).
     *
     * <p><b>Every pre-Phase-12B key is preserved at its exact path</b> — {@code enabled},
     * {@code browserTotal}, {@code browserFailures}, {@code pool}, the {@code pool*} counters, the
     * executor keys, {@code launchOptions}, {@code guestApplyOnlyFlag} and the rest — so no existing
     * consumer of this endpoint breaks. The new material is additive: {@code rollout},
     * {@code runtime}, {@code installation}, {@code session} and {@code lifecycle}.
     *
     * <p>{@code health} is deliberately overwritten by the health service's richer verdict rather
     * than left as {@link #stage}'s queue-depth-only one: a browser that cannot launch at all was
     * previously reported {@code UP} as long as its executor queue was empty, which is exactly the
     * wrong answer for the failure mode this phase exists to catch.
     */
    @GetMapping("/browser")
    public Map<String, Object> browser() {
        Map<String, Object> m = browserMetrics.snapshot();
        Map<String, Object> out = stage(browserEnabled, false, m, browserExecutor,
                (Long) m.get("browserTotal"), (Long) m.get("browserFailures"));
        // Gap D — diagnostics-visibility flag only; real enforcement is GuestApplyEligibility below.
        out.put("guestApplyOnlyFlag", guestApply.isGuestApplyOnlyFlagEnabled());
        out.put("guestApplyEligibleConnectors", java.util.List.of("greenhouse", "lever"));
        // Enterprise Browser Automation — live capacity state. `pool.saturated` plus
        // `poolAcquireTimeouts` is the demand-exceeds-memory-budget signal, and `poolLeasesExpired`
        // is the release-leak signal; both were entirely unobservable before the pool existed.
        out.put("pool", leasePool.snapshot());
        out.putAll(poolMetrics.snapshot());
        out.put("launchOptions", launchOptions.describe());
        out.put("browserRealSubmissions", m.get("browserRealSubmissions"));
        out.put("browserSimulatedSubmissions", m.get("browserSimulatedSubmissions"));
        out.put("captchaOrLoginWallDetected", m.get("browserCaptchaOrLoginWallDetected"));
        out.put("formScreenshotApprovalsPending", m.get("browserFormScreenshotApprovalsPending"));
        out.putAll(browserHealth.report());
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

    /**
     * Phase 7.16.1 — no dedicated executor (verification runs inline within
     * {@code finalizeGuestApplySubmit}'s transaction, not on its own async stage), so this
     * doesn't reuse {@link #stage}; same enabled/health/counts shape otherwise.
     */
    @GetMapping("/submission-verification")
    public Map<String, Object> submissionVerification() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", executionEnabled);
        out.putAll(verificationMetrics.snapshot());
        long total = (long) verificationMetrics.snapshot().get("verificationAttempts");
        long verified = (long) verificationMetrics.snapshot().get("verified");
        String health;
        if (!executionEnabled) health = "NOT_CONFIGURED";
        else if (total == 0) health = "UP";
        else if (verified * 100 < total * 30) health = "DEGRADED"; // fewer than 30% of attempts actually verified
        else health = "UP";
        out.put("health", health);
        return out;
    }

    /**
     * Phase 7.16.3 — Automation Recovery Center diagnostics. No dedicated executor (recovery
     * decisions run inline within {@code ApplicationExecutionService}'s existing transaction; the
     * scheduler that spawns new attempts reuses {@code applicationExecutionExecutor} indirectly via
     * {@code execute()}), so — like {@code /submission-verification} — this doesn't reuse {@link
     * #stage}. Also surfaces the untouched {@code RetryMetrics} (Phase 2E.6 — built, tested, but
     * never wired into a diagnostics endpoint until now) since recovery decisions are RetryPolicyService
     * decisions.
     */
    @GetMapping("/automation-recovery")
    public Map<String, Object> automationRecovery() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", recoveryEnabled);
        out.put("triggerEnabled", recoveryTriggerEnabled);
        Map<String, Object> snapshot = recoveryMetrics.snapshot();
        out.putAll(snapshot);
        out.put("retryPolicy", retryMetrics.snapshot());
        long attempts = (long) snapshot.get("recoveryAttempts");
        double successRate = (double) snapshot.get("recoverySuccessRate");
        String health;
        if (!recoveryEnabled) health = "NOT_CONFIGURED";
        else if (attempts == 0) health = "UP";
        else if (successRate < 30.0) health = "DEGRADED";
        else health = "UP";
        out.put("health", health);
        return out;
    }

    // ── Phase 7.16.4 — Application Operations Center: global, no-PII aggregates over the existing
    // execution/retry/verification/recovery data, gated by application.operations.enabled. Per-
    // application detail (which DOES contain PII) lives on the authenticated ExecutionController
    // instead — never here. ──

    @GetMapping("/operations/summary")
    public Map<String, Object> operationsSummary() {
        if (!operationsEnabled) return Map.of("enabled", false);
        Map<String, Object> out = new LinkedHashMap<>(operations.summary());
        out.put("enabled", true);
        return out;
    }

    @GetMapping("/operations/fleet")
    public Map<String, Object> operationsFleet() {
        if (!operationsEnabled) return Map.of("enabled", false);
        Map<String, Object> out = new LinkedHashMap<>(operations.fleet());
        out.put("enabled", true);
        return out;
    }

    @GetMapping("/operations/queues")
    public Map<String, Object> operationsQueues() {
        if (!operationsEnabled) return Map.of("enabled", false);
        Map<String, Object> out = new LinkedHashMap<>(operations.queues());
        out.put("enabled", true);
        return out;
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
