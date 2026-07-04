package ai.careerpilot.workflow.timeline;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Phase 3A.2 — in-memory counters for the timeline engine. */
@Component
public class TimelineMetrics {

    private final AtomicLong total = new AtomicLong();
    private final AtomicLong appended = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();

    public void recordRequest() { total.incrementAndGet(); }
    public void recordAppend() { appended.incrementAndGet(); }
    public void recordFailure() { failures.incrementAndGet(); }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("timelineTotal", total.get());
        out.put("timelineAppended", appended.get());
        out.put("timelineFailures", failures.get());
        return out;
    }
}
