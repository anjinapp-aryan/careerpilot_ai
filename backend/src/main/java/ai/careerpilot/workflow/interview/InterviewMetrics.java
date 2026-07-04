package ai.careerpilot.workflow.interview;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Phase 3A.4 — in-memory counters for the interview tracking engine. */
@Component
public class InterviewMetrics {

    private final AtomicLong total = new AtomicLong();
    private final AtomicLong detected = new AtomicLong();
    private final AtomicLong recorded = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();

    public void recordRequest() { total.incrementAndGet(); }
    public void recordDetected() { detected.incrementAndGet(); }
    public void recordRecorded() { recorded.incrementAndGet(); }
    public void recordFailure() { failures.incrementAndGet(); }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("interviewTotal", total.get());
        out.put("interviewDetected", detected.get());
        out.put("interviewRecorded", recorded.get());
        out.put("interviewFailures", failures.get());
        return out;
    }
}
