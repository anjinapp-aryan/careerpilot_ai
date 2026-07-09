package ai.careerpilot.execution.ats;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 2E.3 — in-memory counters for the ATS connector framework. {@code stubRejections} counts
 * how often an unconfigured connector was reached (always, in the 2E build).
 */
@Component
public class AtsConnectorMetrics {

    private final AtomicLong total = new AtomicLong();
    private final AtomicLong detected = new AtomicLong();
    private final AtomicLong stubRejections = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();

    public void recordRequest() { total.incrementAndGet(); }
    public void recordDetected() { detected.incrementAndGet(); }
    public void recordStubRejection() { stubRejections.incrementAndGet(); }
    public void recordFailure() { failures.incrementAndGet(); }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("atsTotal", total.get());
        out.put("atsDetected", detected.get());
        out.put("atsStubRejections", stubRejections.get());
        out.put("atsFailures", failures.get());
        return out;
    }
}
