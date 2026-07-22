package ai.careerpilot.execution.recovery;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Phase 7.16.3 — in-memory counters for the Automation Recovery Center, same shape as {@code RetryMetrics}/{@code VerificationMetrics}. */
@Component
public class RecoveryMetrics {

    private final AtomicLong attempts = new AtomicLong();
    private final AtomicLong retryScheduled = new AtomicLong();
    private final AtomicLong manualReview = new AtomicLong();
    private final AtomicLong stopped = new AtomicLong();
    private final AtomicLong recoverySuccess = new AtomicLong();
    private final AtomicLong recoveryFailure = new AtomicLong();
    private final AtomicLong browserRestarts = new AtomicLong();
    private final AtomicLong cancellations = new AtomicLong();
    private final AtomicLong latencyTotalMs = new AtomicLong();

    public void recordAttempt() { attempts.incrementAndGet(); }
    public void recordRetryScheduled() { retryScheduled.incrementAndGet(); }
    public void recordManualReview() { manualReview.incrementAndGet(); }
    public void recordStopped() { stopped.incrementAndGet(); }
    public void recordRecoverySuccess() { recoverySuccess.incrementAndGet(); }
    public void recordRecoveryFailure() { recoveryFailure.incrementAndGet(); }
    public void recordBrowserRestart() { browserRestarts.incrementAndGet(); }
    public void recordCancellation() { cancellations.incrementAndGet(); }
    public void recordLatency(long ms) { latencyTotalMs.addAndGet(ms); }

    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        long a = attempts.get();
        long recovered = recoverySuccess.get();
        long recoveryAttempts = recovered + recoveryFailure.get();
        m.put("recoveryAttempts", a);
        m.put("retryScheduled", retryScheduled.get());
        m.put("manualReview", manualReview.get());
        m.put("stopped", stopped.get());
        m.put("recoverySuccess", recovered);
        m.put("recoveryFailure", recoveryFailure.get());
        m.put("browserRestartCount", browserRestarts.get());
        m.put("cancellations", cancellations.get());
        m.put("recoverySuccessRate", recoveryAttempts == 0 ? 0.0 : (recovered * 100.0 / recoveryAttempts));
        m.put("avgRecoveryLatencyMs", a == 0 ? 0.0 : (latencyTotalMs.get() * 1.0 / a));
        return m;
    }
}
