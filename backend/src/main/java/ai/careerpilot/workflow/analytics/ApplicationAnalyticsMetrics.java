package ai.careerpilot.workflow.analytics;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Phase 3A.5 — in-memory counters for the application analytics engine. */
@Component
public class ApplicationAnalyticsMetrics {

    private final AtomicLong total = new AtomicLong();
    private final AtomicLong recomputes = new AtomicLong();
    private final AtomicLong metricsWritten = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();

    public void recordRequest() { total.incrementAndGet(); }
    public void recordRecompute() { recomputes.incrementAndGet(); }
    public void recordMetricWritten() { metricsWritten.incrementAndGet(); }
    public void recordFailure() { failures.incrementAndGet(); }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("analyticsTotal", total.get());
        out.put("analyticsRecomputes", recomputes.get());
        out.put("analyticsMetricsWritten", metricsWritten.get());
        out.put("analyticsFailures", failures.get());
        return out;
    }
}
