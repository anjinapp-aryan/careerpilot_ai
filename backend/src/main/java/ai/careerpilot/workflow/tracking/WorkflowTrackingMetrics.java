package ai.careerpilot.workflow.tracking;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Phase 3A.1 — in-memory counters for the application lifecycle engine. */
@Component
public class WorkflowTrackingMetrics {

    private final AtomicLong total = new AtomicLong();
    private final AtomicLong created = new AtomicLong();
    private final AtomicLong transitions = new AtomicLong();
    private final AtomicLong rejectedTransitions = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();

    public void recordRequest() { total.incrementAndGet(); }
    public void recordCreate() { created.incrementAndGet(); }
    public void recordTransition() { transitions.incrementAndGet(); }
    public void recordRejectedTransition() { rejectedTransitions.incrementAndGet(); }
    public void recordFailure() { failures.incrementAndGet(); }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("trackingTotal", total.get());
        out.put("trackingCreated", created.get());
        out.put("trackingTransitions", transitions.get());
        out.put("trackingInvalidTransitions", rejectedTransitions.get());
        out.put("trackingFailures", failures.get());
        return out;
    }
}
