package ai.careerpilot.execution.browser;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Phase 12B — counters for the <em>browser process lifecycle</em>: launching Chromium, crashing,
 * and being restarted.
 *
 * <p>This is deliberately a third metrics class rather than an addition to an existing one, because
 * this codebase already separates browser metrics by the question they answer and merging them
 * would lose that:
 * <ul>
 *   <li>{@link BrowserAutomationMetrics} — <b>submission outcomes</b> (real submits, CAPTCHA walls,
 *       approvals pending). Read by product/ops when asking "are applications going through?"</li>
 *   <li>{@code BrowserPoolMetrics} — <b>capacity</b> (leases, waits, timeouts, expiries). Read when
 *       asking "is the concurrency budget the bottleneck?"</li>
 *   <li>this class — <b>process health</b> (can Chromium start at all, is it crashing, how often is
 *       it being restarted). Read when asking "is the browser itself broken?"</li>
 * </ul>
 *
 * <p>Every field here was previously unobservable. Before Phase 12B a Chromium that failed to launch
 * produced a stack trace in the log and nothing else — no counter, no last-error string, no
 * endpoint. On the target deployment (ARM, distro Chromium, no sandbox) launch failure is the single
 * most likely first-time failure mode, so it is the one thing that most needed a signal.
 *
 * <p>Hand-rolled {@link AtomicLong} counters, matching the established convention in this codebase
 * ({@code AiMetrics}, {@code BrowserPoolMetrics}) rather than introducing Micrometer for one package.
 */
@Component
public class BrowserLifecycleMetrics {

    /** Truncated so a diagnostics response can never be used to exfiltrate page content via an error. */
    private static final int MAX_ERROR_CHARS = 400;

    private final AtomicLong launchAttempts = new AtomicLong();
    private final AtomicLong launchSuccesses = new AtomicLong();
    private final AtomicLong launchFailures = new AtomicLong();
    private final AtomicLong restarts = new AtomicLong();
    private final AtomicLong crashes = new AtomicLong();

    private final AtomicLong launchDurationSumMs = new AtomicLong();
    private final AtomicLong launchDurationCount = new AtomicLong();
    private final AtomicLong maxLaunchDurationMs = new AtomicLong();

    private final AtomicReference<Instant> lastLaunchAt = new AtomicReference<>();
    private final AtomicReference<Instant> lastLaunchFailureAt = new AtomicReference<>();
    private final AtomicReference<Instant> lastCrashAt = new AtomicReference<>();
    private final AtomicReference<Instant> lastRestartAt = new AtomicReference<>();
    private final AtomicReference<String> lastLaunchError = new AtomicReference<>();
    private final AtomicReference<String> lastCrashReason = new AtomicReference<>();

    public void recordLaunchAttempt() {
        launchAttempts.incrementAndGet();
    }

    public void recordLaunchSuccess(long durationMs) {
        launchSuccesses.incrementAndGet();
        launchDurationSumMs.addAndGet(durationMs);
        launchDurationCount.incrementAndGet();
        maxLaunchDurationMs.updateAndGet(prev -> Math.max(prev, durationMs));
        lastLaunchAt.set(Instant.now());
    }

    public void recordLaunchFailure(String error) {
        launchFailures.incrementAndGet();
        lastLaunchFailureAt.set(Instant.now());
        lastLaunchError.set(truncate(error));
    }

    public void recordCrash(String reason) {
        crashes.incrementAndGet();
        lastCrashAt.set(Instant.now());
        lastCrashReason.set(truncate(reason));
    }

    public void recordRestart() {
        restarts.incrementAndGet();
        lastRestartAt.set(Instant.now());
    }

    /**
     * Launch success rate as a percentage. Returns {@code 100.0} when nothing has been attempted —
     * "never tried" is not a failure, and reporting 0% for an idle system would make every health
     * check on a dark deployment read as broken.
     */
    public double launchSuccessRate() {
        long attempts = launchAttempts.get();
        if (attempts == 0) return 100.0;
        return (launchSuccesses.get() * 100.0) / attempts;
    }

    public long launchFailureCount() {
        return launchFailures.get();
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("launchAttempts", launchAttempts.get());
        out.put("launchSuccesses", launchSuccesses.get());
        out.put("launchFailures", launchFailures.get());
        out.put("launchSuccessRate", round(launchSuccessRate()));
        long count = launchDurationCount.get();
        out.put("avgLaunchTimeMs", count == 0 ? 0 : launchDurationSumMs.get() / count);
        out.put("maxLaunchTimeMs", maxLaunchDurationMs.get());
        out.put("browserRestarts", restarts.get());
        out.put("browserCrashes", crashes.get());
        out.put("lastLaunchAt", asString(lastLaunchAt.get()));
        out.put("lastLaunchFailureAt", asString(lastLaunchFailureAt.get()));
        out.put("lastLaunchError", lastLaunchError.get());
        out.put("lastCrashAt", asString(lastCrashAt.get()));
        out.put("lastCrashReason", lastCrashReason.get());
        out.put("lastRestartAt", asString(lastRestartAt.get()));
        return out;
    }

    private static String asString(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() <= MAX_ERROR_CHARS ? s : s.substring(0, MAX_ERROR_CHARS) + "…";
    }
}
