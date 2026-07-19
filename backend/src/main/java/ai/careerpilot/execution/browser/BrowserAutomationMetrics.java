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
        return out;
    }
}
