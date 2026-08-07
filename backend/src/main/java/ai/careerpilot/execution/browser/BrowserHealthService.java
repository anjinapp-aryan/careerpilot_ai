package ai.careerpilot.execution.browser;

import ai.careerpilot.execution.browser.pool.BrowserLaunchOptionsFactory;
import ai.careerpilot.execution.browser.pool.BrowserLeasePool;
import ai.careerpilot.execution.browser.pool.BrowserPoolMetrics;
import ai.careerpilot.execution.browser.rollout.BrowserRolloutGate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Phase 12B — assembles the single browser-health report served by
 * {@code GET /api/diagnostics/browser}.
 *
 * <p>Deliberately a read-only aggregator over components that already exist, in the same shape as
 * {@code CareerContextService} (Phase 11A): it owns no state, starts nothing, and computes nothing
 * that any of its sources does not already know. In particular it never triggers a lazy browser
 * launch — an operator checking health on a dark deployment must not be the thing that starts
 * Chromium.
 *
 * <h2>Honesty rules applied here</h2>
 * <ul>
 *   <li><b>Chromium version</b> is reported only when the browser is actually launched. There is no
 *       {@code chromium --version} subprocess: this endpoint is unauthenticated, and spawning a
 *       process on request is not worth a version string. Unlaunched reports {@code null}.</li>
 *   <li><b>{@code browserInstalled}</b> is a real filesystem check of the configured executable, not
 *       an assumption. When no explicit path is configured it reports {@code null} ("unknown"),
 *       because Playwright's own bundled-browser resolution is internal and guessing at its cache
 *       layout would be fabricating a fact.</li>
 *   <li><b>{@code openPages}</b> equals the active lease count and says so. Each lease owns exactly
 *       one page ({@code BrowserLeasePool#acquire}), so a separate page counter would be a second
 *       name for the same number — and two counters that can disagree is precisely how a metric
 *       stops being trusted.</li>
 *   <li><b>ARM compatibility</b> is a genuine derivation, not a label: on {@code aarch64} Playwright
 *       publishes no Chromium, so a build with neither an executable path nor a channel configured
 *       is reported as incompatible <em>before</em> anyone discovers it by turning the flag on.</li>
 * </ul>
 */
@Service
public class BrowserHealthService {

    private final BrowserSessionManager sessionManager;
    private final BrowserLeasePool leasePool;
    private final BrowserPoolMetrics poolMetrics;
    private final BrowserLifecycleMetrics lifecycleMetrics;
    private final BrowserAutomationMetrics automationMetrics;
    private final BrowserLaunchOptionsFactory launchOptions;
    private final BrowserRolloutGate rolloutGate;
    private final ai.careerpilot.execution.browser.form.FormAutomationMetrics formMetrics;
    private final ai.careerpilot.execution.browser.validation.BrowserValidationMetrics validationMetrics;
    private final ai.careerpilot.execution.browser.validation.ValidationUrlPolicy urlPolicy;
    private final ai.careerpilot.execution.browser.validation.ValidationHistoryService validationHistory;

    private final boolean automationEnabled;
    private final String configuredExecutablePath;

    /** Optional: retention is a separate concern with its own flag, so it may be absent. */
    private final org.springframework.beans.factory.ObjectProvider<
            ai.careerpilot.retention.ScreenshotRetentionService> screenshotRetention;

    public BrowserHealthService(BrowserSessionManager sessionManager,
                                BrowserLeasePool leasePool,
                                BrowserPoolMetrics poolMetrics,
                                BrowserLifecycleMetrics lifecycleMetrics,
                                BrowserAutomationMetrics automationMetrics,
                                BrowserLaunchOptionsFactory launchOptions,
                                BrowserRolloutGate rolloutGate,
                                ai.careerpilot.execution.browser.form.FormAutomationMetrics formMetrics,
                                ai.careerpilot.execution.browser.validation.BrowserValidationMetrics validationMetrics,
                                ai.careerpilot.execution.browser.validation.ValidationUrlPolicy urlPolicy,
                                ai.careerpilot.execution.browser.validation.ValidationHistoryService validationHistory,
                                org.springframework.beans.factory.ObjectProvider<
                                        ai.careerpilot.retention.ScreenshotRetentionService> screenshotRetention,
                                @Value("${browser.automation.enabled:false}") boolean automationEnabled,
                                @Value("${browser.automation.launch.executable-path:}") String configuredExecutablePath) {
        this.screenshotRetention = screenshotRetention;
        this.sessionManager = sessionManager;
        this.leasePool = leasePool;
        this.poolMetrics = poolMetrics;
        this.lifecycleMetrics = lifecycleMetrics;
        this.automationMetrics = automationMetrics;
        this.launchOptions = launchOptions;
        this.rolloutGate = rolloutGate;
        this.formMetrics = formMetrics;
        this.validationMetrics = validationMetrics;
        this.urlPolicy = urlPolicy;
        this.validationHistory = validationHistory;
        this.automationEnabled = automationEnabled;
        this.configuredExecutablePath = configuredExecutablePath == null ? "" : configuredExecutablePath.trim();
    }

    /** The full report. Never throws — a diagnostics endpoint that can fail is not a diagnostic. */
    public Map<String, Object> report() {
        Map<String, Object> out = new LinkedHashMap<>();

        out.put("enabled", automationEnabled);
        out.put("rollout", safelyMap(rolloutGate::snapshot));
        out.put("runtime", runtime());
        out.put("installation", installation());
        out.put("session", session());
        out.put("capacity", safelyMap(leasePool::snapshot));
        out.putAll(safelyMap(poolMetrics::snapshot));
        // Submission outcomes (avg submit duration, last screenshot/confirmation timestamps) live
        // here rather than in a section of their own, so the pre-Phase-12B top-level keys that
        // existing callers already read (browserTotal, browserFailures, ...) keep their exact paths.
        out.putAll(safelyMap(automationMetrics::snapshot));
        out.put("lifecycle", safelyMap(lifecycleMetrics::snapshot));
        // Phase 12C — form-filling health. `formUploadFailures` is the one to watch: it counts
        // uploads the engine refused to claim because it could not read the file back off the
        // input, which is the difference between an honest failure and an application delivered
        // with no resume attached.
        out.put("formEngine", safelyMap(formMetrics::snapshot));
        // Phase 12C.5 — last validation, timings, selector coverage, automation confidence and the
        // per-ATS compatibility picture. Extends this endpoint rather than adding one, so an
        // operator has a single place to look before advancing a rollout stage.
        out.put("validation", safelyMap(validationMetrics::snapshot));
        out.put("validationPolicy", safelyMap(urlPolicy::describe));
        // P2 WI3 — screenshot retention, on the same page as the screenshots it governs.
        out.put("screenshotRetention", safelyMap(() -> {
            var retention = screenshotRetention.getIfAvailable();
            return retention == null ? java.util.Map.of("enabled", false) : retention.snapshot();
        }));
        // Phase 13A — the ATS Validation Campaign dashboard: pages tested, average confidence,
        // control quality and trend per platform, from durable history rather than the in-memory
        // latest-only map above. Aggregates only — no URL, no user id on this public endpoint.
        out.put("validationCampaign", safelyMap(validationHistory::campaignReport));
        out.put("launchOptions", safelyMap(launchOptions::describe));
        // The verdict is guarded too. It reads several collaborators, and a report that can be
        // taken down by one of them is worse than no report — an operator checking health during an
        // incident is exactly who cannot afford a 500.
        String health;
        try {
            health = healthState();
        } catch (Exception e) {
            health = "UNKNOWN";
        }
        out.put("health", health);
        return out;
    }

    // ── sections ──

    private Map<String, Object> runtime() {
        Map<String, Object> out = new LinkedHashMap<>();
        String arch = System.getProperty("os.arch", "unknown");
        out.put("osArch", arch);
        out.put("osName", System.getProperty("os.name", "unknown"));

        boolean arm = arch.toLowerCase(Locale.ROOT).contains("aarch64")
                || arch.toLowerCase(Locale.ROOT).contains("arm");
        out.put("arm", arm);
        Map<String, Object> describe = safelyMap(launchOptions::describe);
        boolean explicitBrowserConfigured = !configuredExecutablePath.isEmpty()
                || !"(none)".equals(String.valueOf(describe.get("channel")));
        // On ARM, Playwright publishes no linux-arm64 Chromium — so "compatible" means an explicit
        // distro browser has been pointed at. On x86 the bundled download is a valid route.
        out.put("armCompatible", !arm || explicitBrowserConfigured);
        out.put("armCompatibilityNote", arm && !explicitBrowserConfigured
                ? "aarch64 with no executable-path or channel configured — Playwright ships no "
                        + "linux-arm64 Chromium, so launch would fail"
                : null);

        Runtime rt = Runtime.getRuntime();
        Map<String, Object> memory = new LinkedHashMap<>();
        memory.put("jvmMaxMb", rt.maxMemory() / (1024 * 1024));
        memory.put("jvmTotalMb", rt.totalMemory() / (1024 * 1024));
        memory.put("jvmUsedMb", (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024));
        memory.put("jvmFreeMb", rt.freeMemory() / (1024 * 1024));
        memory.put("availableProcessors", rt.availableProcessors());
        // Stated explicitly because it is a real limitation, not an oversight: Chromium is a child
        // process, so its RSS is outside this JVM's view. Container-level memory is the only place
        // total browser memory can be observed.
        memory.put("note", "JVM heap only — Chromium runs as a child process and is not counted here");
        out.put("memory", memory);
        return out;
    }

    private Map<String, Object> installation() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("executablePath", configuredExecutablePath.isEmpty() ? null : configuredExecutablePath);
        if (configuredExecutablePath.isEmpty()) {
            // Unknown, not false. Playwright's bundled-browser location is internal to the driver;
            // asserting either way would be a guess.
            out.put("browserInstalled", null);
            out.put("browserInstalledNote",
                    "no explicit executable-path configured — relying on Playwright's bundled browser, "
                            + "whose presence cannot be verified from here");
            return out;
        }
        Boolean installed;
        try {
            Path path = Path.of(configuredExecutablePath);
            installed = Files.isRegularFile(path) && Files.isExecutable(path);
        } catch (Exception e) {
            installed = null;
        }
        out.put("browserInstalled", installed);
        out.put("browserInstalledNote", Boolean.FALSE.equals(installed)
                ? "configured executable-path does not exist or is not executable — launch will fail"
                : null);
        return out;
    }

    private Map<String, Object> session() {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean launched = sessionManager.isLaunched();
        out.put("launched", launched);
        out.put("headless", true); // BrowserLaunchOptionsFactory always sets setHeadless(true).
        Map<String, Object> describe = safelyMap(launchOptions::describe);
        Object noSandbox = describe.get("noSandbox");
        out.put("sandboxDisabled", noSandbox);
        out.put("chromiumVersion", launched ? sessionManager.browserVersion() : null);
        out.put("openContexts", sessionManager.openContexts());
        int activeLeases = leasePool.activeLeases();
        out.put("openPages", activeLeases);
        out.put("openPagesNote", "one page per lease by construction — same number as activeLeases");
        Object lastOpened = sessionManager.lastContextOpenedAt();
        out.put("lastContextOpenedAt", lastOpened == null ? null : lastOpened.toString());
        // P3 — recycle state. Added to the existing session section rather than a new one so every
        // pre-existing key keeps its exact path. `launchedAt == null` while `launched` is true is
        // impossible; both are null-safe here so an operator reading a dark deployment sees nulls
        // rather than a 500.
        java.time.Instant launchedAt = sessionManager.launchedAt();
        java.time.Instant lastActivity = sessionManager.lastActivityAt();
        out.put("launchedAt", launchedAt == null ? null : launchedAt.toString());
        out.put("lastActivityAt", lastActivity == null ? null : lastActivity.toString());
        out.put("contextsSinceLaunch", sessionManager.contextsSinceLaunch());
        out.put("uptimeSeconds", launchedAt == null
                ? null : java.time.Duration.between(launchedAt, java.time.Instant.now()).toSeconds());
        out.put("idleSeconds", lastActivity == null
                ? null : java.time.Duration.between(lastActivity, java.time.Instant.now()).toSeconds());
        out.put("zombie", safeZombie());
        return out;
    }

    /**
     * The single verdict. Ordering matters and is deliberate: a state that makes automation
     * <em>impossible</em> (missing browser, ARM misconfiguration, failing launches) outranks one
     * that merely makes it <em>slow</em> (saturation), because the operator response is different —
     * the first is a rollback, the second is a capacity decision.
     */
    private String healthState() {
        if (!automationEnabled) return "NOT_CONFIGURED";

        Map<String, Object> install = installation();
        if (Boolean.FALSE.equals(install.get("browserInstalled"))) return "DOWN";
        if (Boolean.FALSE.equals(runtime().get("armCompatible"))) return "DOWN";

        long launchFailures = lifecycleMetrics.launchFailureCount();
        if (launchFailures > 0 && !sessionManager.isLaunched()) return "DOWN";
        if (lifecycleMetrics.launchSuccessRate() < 100.0) return "DEGRADED";
        if (safeZombie()) return "DEGRADED";

        Map<String, Object> pool = safelyMap(poolMetrics::snapshot);
        if (asLong(pool.get("poolAcquireTimeouts")) > 0) return "DEGRADED";
        if (asLong(pool.get("poolLeasesExpired")) > 0) return "DEGRADED";
        if (leasePool.isSaturated() && leasePool.activeLeases() > 0) return "DEGRADED";
        return "UP";
    }

    // ── helpers ──

    private boolean safeZombie() {
        try {
            return sessionManager.isZombie();
        } catch (Exception e) {
            return false;
        }
    }

    private static long asLong(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    /** One misbehaving source degrades its own section to an error marker, never the whole report. */
    private static Map<String, Object> safelyMap(java.util.function.Supplier<Map<String, Object>> supplier) {
        try {
            Map<String, Object> value = supplier.get();
            return value == null ? Map.of() : value;
        } catch (Exception e) {
            return Map.of("unavailable", true, "error", String.valueOf(e));
        }
    }
}
