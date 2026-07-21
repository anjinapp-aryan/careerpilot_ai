package ai.careerpilot.execution.verification;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Phase 7.16.1 — in-memory counters, same shape as {@code ApplicationExecutionMetrics}/{@code RetryMetrics}. */
@Component
public class VerificationMetrics {

    private final AtomicLong attempts = new AtomicLong();
    private final AtomicLong verified = new AtomicLong();
    private final AtomicLong notVerified = new AtomicLong();
    private final AtomicLong unableToVerify = new AtomicLong();
    private final AtomicLong unknown = new AtomicLong();
    private final AtomicLong latencyTotalMs = new AtomicLong();

    public void recordAttempt() { attempts.incrementAndGet(); }
    public void recordVerified() { verified.incrementAndGet(); }
    public void recordNotVerified() { notVerified.incrementAndGet(); }
    public void recordUnableToVerify() { unableToVerify.incrementAndGet(); }
    public void recordUnknown() { unknown.incrementAndGet(); }
    public void recordLatency(long ms) { latencyTotalMs.addAndGet(ms); }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        long total = attempts.get();
        out.put("verificationAttempts", total);
        out.put("verified", verified.get());
        out.put("notVerified", notVerified.get());
        out.put("unableToVerify", unableToVerify.get());
        out.put("unknown", unknown.get());
        out.put("verificationSuccessRate", total == 0 ? 0.0 : (double) verified.get() / total);
        out.put("avgVerificationLatencyMs", total == 0 ? 0.0 : (double) latencyTotalMs.get() / total);
        return out;
    }
}
