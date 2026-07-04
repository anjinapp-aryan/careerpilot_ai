package ai.careerpilot.execution.tracking;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Phase 2E.7 — in-memory counters for the application tracking engine. */
@Component
public class TrackingMetrics {

    private final AtomicLong total = new AtomicLong();
    private final AtomicLong transitions = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();

    public void recordRequest() { total.incrementAndGet(); }
    public void recordTransition() { transitions.incrementAndGet(); }
    public void recordFailure() { failures.incrementAndGet(); }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("trackingTotal", total.get());
        out.put("trackingTransitions", transitions.get());
        out.put("trackingFailures", failures.get());
        return out;
    }
}
