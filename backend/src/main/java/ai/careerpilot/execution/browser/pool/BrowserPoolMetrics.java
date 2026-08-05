package ai.careerpilot.execution.browser.pool;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Enterprise Browser Automation — pool-level counters, kept separate from
 * {@code BrowserAutomationMetrics} (which measures <em>submission outcomes</em>: real submissions,
 * CAPTCHA walls, approvals). This one measures <em>capacity</em>. They answer different questions
 * and are read by different people, so they are not merged.
 *
 * <p>Hand-rolled {@link AtomicLong} counters, matching the established convention in this codebase
 * ({@code AiMetrics}, {@code InMemoryMcpMetrics}, {@code BrowserAutomationMetrics}) rather than
 * introducing Micrometer for one package.
 *
 * <p>The operationally important signals here are {@code acquireTimeouts} and
 * {@code leasesExpired}: a non-zero {@code acquireTimeouts} means demand exceeds the configured
 * memory budget, and a non-zero {@code leasesExpired} means some code path is failing to release —
 * a leak that would previously have been invisible until the browser wedged.
 */
@Component
public class BrowserPoolMetrics {

    private final AtomicLong acquired = new AtomicLong();
    private final AtomicLong released = new AtomicLong();
    private final AtomicLong acquireTimeouts = new AtomicLong();
    private final AtomicLong acquireFailures = new AtomicLong();
    private final AtomicLong leasesExpired = new AtomicLong();

    private final AtomicLong waitSumMs = new AtomicLong();
    private final AtomicLong waitCount = new AtomicLong();
    private final AtomicLong maxWaitMs = new AtomicLong();

    private final AtomicLong leaseAgeSumMs = new AtomicLong();
    private final AtomicLong leaseAgeCount = new AtomicLong();
    private final AtomicLong maxLeaseAgeMs = new AtomicLong();

    private final AtomicLong peakActive = new AtomicLong();

    public void recordAcquired(int activeNow) {
        acquired.incrementAndGet();
        peakActive.updateAndGet(prev -> Math.max(prev, activeNow));
    }

    public void recordReleased(long ageMs, int activeNow) {
        released.incrementAndGet();
        leaseAgeSumMs.addAndGet(ageMs);
        leaseAgeCount.incrementAndGet();
        maxLeaseAgeMs.updateAndGet(prev -> Math.max(prev, ageMs));
    }

    public void recordAcquireWait(long ms) {
        waitSumMs.addAndGet(ms);
        waitCount.incrementAndGet();
        maxWaitMs.updateAndGet(prev -> Math.max(prev, ms));
    }

    public void recordAcquireTimeout() { acquireTimeouts.incrementAndGet(); }
    public void recordAcquireFailure() { acquireFailures.incrementAndGet(); }
    public void recordExpired() { leasesExpired.incrementAndGet(); }

    /** Leases handed out but not yet returned, derived rather than tracked separately. */
    public long outstanding() {
        return Math.max(0, acquired.get() - released.get() - leasesExpired.get());
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("poolAcquired", acquired.get());
        out.put("poolReleased", released.get());
        out.put("poolOutstanding", outstanding());
        out.put("poolAcquireTimeouts", acquireTimeouts.get());
        out.put("poolAcquireFailures", acquireFailures.get());
        out.put("poolLeasesExpired", leasesExpired.get());
        out.put("poolPeakActive", peakActive.get());
        long waits = waitCount.get();
        out.put("poolAvgAcquireWaitMs", waits == 0 ? 0 : waitSumMs.get() / waits);
        out.put("poolMaxAcquireWaitMs", maxWaitMs.get());
        long ages = leaseAgeCount.get();
        out.put("poolAvgLeaseAgeMs", ages == 0 ? 0 : leaseAgeSumMs.get() / ages);
        out.put("poolMaxLeaseAgeMs", maxLeaseAgeMs.get());
        return out;
    }
}
