package ai.careerpilot.execution.browser;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 2E.2 / Gap D — in-memory counters for the browser-automation layer. {@code stubRejections}
 * tracks how often a non-guest-apply-eligible connector or the disabled provider was reached;
 * {@code realSubmissions}/{@code simulatedSubmissions} distinguish an actual Playwright submit
 * click from the pre-Gap-D "treat HUMAN_REVIEW as submitted" simulated path; {@code
 * captchaOrLoginWallDetected} and {@code formScreenshotApprovalsPending} back the new Gap D
 * diagnostics (see {@code ExecutionDiagnosticsController#browser()}).
 */
@Component
public class BrowserAutomationMetrics {

    private final AtomicLong total = new AtomicLong();
    private final AtomicLong stubRejections = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong latencySumMs = new AtomicLong();
    private final AtomicLong latencyCount = new AtomicLong();

    private final AtomicLong realSubmissions = new AtomicLong();
    private final AtomicLong simulatedSubmissions = new AtomicLong();
    private final AtomicLong captchaOrLoginWallDetected = new AtomicLong();
    private final AtomicLong formScreenshotApprovalsPending = new AtomicLong();

    // ── Phase 12B — evidence-capture liveness. These two timestamps answer the operational
    // question "is the evidence pipeline still working?" separately from "are submissions
    // succeeding?". A screenshot that stopped being captured is how a silent Chromium
    // rendering failure would first show up — the submit path can keep 'working' while the
    // FORM_SCREENSHOT approval gate is looking at blank images. ──
    private final AtomicLong screenshotsCaptured = new AtomicLong();
    private final AtomicLong screenshotFailures = new AtomicLong();
    private final java.util.concurrent.atomic.AtomicReference<java.time.Instant> lastScreenshotAt =
            new java.util.concurrent.atomic.AtomicReference<>();
    private final java.util.concurrent.atomic.AtomicReference<java.time.Instant> lastConfirmationAt =
            new java.util.concurrent.atomic.AtomicReference<>();

    // Submit duration is tracked separately from the general attempt latency above: an attemptFill
    // and a finalizeSubmit have very different expected durations, and averaging them together
    // hides a slow submit behind fast fills.
    private final AtomicLong submitLatencySumMs = new AtomicLong();
    private final AtomicLong submitLatencyCount = new AtomicLong();
    private final AtomicLong maxSubmitLatencyMs = new AtomicLong();

    public void recordRequest() { total.incrementAndGet(); }
    public void recordStubRejection() { stubRejections.incrementAndGet(); }
    public void recordFailure() { failures.incrementAndGet(); }
    public void recordRealSubmission() { realSubmissions.incrementAndGet(); }
    public void recordSimulatedSubmission() { simulatedSubmissions.incrementAndGet(); }
    public void recordCaptchaOrLoginWallDetected() { captchaOrLoginWallDetected.incrementAndGet(); }
    public void recordFormScreenshotApprovalPending() { formScreenshotApprovalsPending.incrementAndGet(); }
    public void recordFormScreenshotApprovalResolved() { formScreenshotApprovalsPending.decrementAndGet(); }

    public void recordLatency(long ms) {
        latencySumMs.addAndGet(ms);
        latencyCount.incrementAndGet();
    }

    // ── Phase 12B ──

    public void recordScreenshotCaptured() {
        screenshotsCaptured.incrementAndGet();
        lastScreenshotAt.set(java.time.Instant.now());
    }

    public void recordScreenshotFailure() {
        screenshotFailures.incrementAndGet();
    }

    public void recordConfirmationCaptured() {
        lastConfirmationAt.set(java.time.Instant.now());
    }

    public void recordSubmitLatency(long ms) {
        submitLatencySumMs.addAndGet(ms);
        submitLatencyCount.incrementAndGet();
        maxSubmitLatencyMs.updateAndGet(prev -> Math.max(prev, ms));
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("browserTotal", total.get());
        out.put("browserStubRejections", stubRejections.get());
        out.put("browserFailures", failures.get());
        out.put("browserRealSubmissions", realSubmissions.get());
        out.put("browserSimulatedSubmissions", simulatedSubmissions.get());
        out.put("browserCaptchaOrLoginWallDetected", captchaOrLoginWallDetected.get());
        out.put("browserFormScreenshotApprovalsPending", formScreenshotApprovalsPending.get());
        long count = latencyCount.get();
        out.put("browserAvgLatencyMs", count == 0 ? 0 : latencySumMs.get() / count);

        // Phase 12B
        out.put("browserScreenshotsCaptured", screenshotsCaptured.get());
        out.put("browserScreenshotFailures", screenshotFailures.get());
        out.put("browserLastScreenshotAt", asString(lastScreenshotAt.get()));
        out.put("browserLastConfirmationAt", asString(lastConfirmationAt.get()));
        long submits = submitLatencyCount.get();
        out.put("browserAvgSubmitDurationMs", submits == 0 ? 0 : submitLatencySumMs.get() / submits);
        out.put("browserMaxSubmitDurationMs", maxSubmitLatencyMs.get());
        return out;
    }

    private static String asString(java.time.Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
