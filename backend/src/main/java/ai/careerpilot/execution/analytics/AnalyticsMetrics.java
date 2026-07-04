package ai.careerpilot.execution.analytics;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Phase 2E.8 — in-memory counters for the execution analytics engine. */
@Component
public class AnalyticsMetrics {

    private final AtomicLong total = new AtomicLong();
    private final AtomicLong snapshotsWritten = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();

    public void recordRequest() { total.incrementAndGet(); }
    public void recordSnapshot() { snapshotsWritten.incrementAndGet(); }
    public void recordFailure() { failures.incrementAndGet(); }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("analyticsTotal", total.get());
        out.put("analyticsSnapshotsWritten", snapshotsWritten.get());
        out.put("analyticsFailures", failures.get());
        return out;
    }
}
