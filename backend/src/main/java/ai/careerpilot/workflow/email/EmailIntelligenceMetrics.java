package ai.careerpilot.workflow.email;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Phase 3A.3 — in-memory counters for the email intelligence engine. */
@Component
public class EmailIntelligenceMetrics {

    private final AtomicLong total = new AtomicLong();
    private final AtomicLong classified = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();

    public void recordRequest() { total.incrementAndGet(); }
    public void recordClassified() { classified.incrementAndGet(); }
    public void recordFailure() { failures.incrementAndGet(); }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("emailTotal", total.get());
        out.put("emailClassified", classified.get());
        out.put("emailFailures", failures.get());
        return out;
    }
}
