package ai.careerpilot.workflow.career;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Phase 3A.6 — in-memory counters for the career intelligence engine. */
@Component
public class CareerIntelligenceMetrics {

    private final AtomicLong total = new AtomicLong();
    private final AtomicLong recomputes = new AtomicLong();
    private final AtomicLong dimensionsWritten = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();

    public void recordRequest() { total.incrementAndGet(); }
    public void recordRecompute() { recomputes.incrementAndGet(); }
    public void recordDimensionWritten() { dimensionsWritten.incrementAndGet(); }
    public void recordFailure() { failures.incrementAndGet(); }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("careerTotal", total.get());
        out.put("careerRecomputes", recomputes.get());
        out.put("careerDimensionsWritten", dimensionsWritten.get());
        out.put("careerFailures", failures.get());
        return out;
    }
}
