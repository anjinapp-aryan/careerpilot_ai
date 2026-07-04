package ai.careerpilot.execution.safety;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Phase 2E.5 — in-memory counters for the safety engine, tallied per verdict. */
@Component
public class SafetyMetrics {

    private final AtomicLong total = new AtomicLong();
    private final AtomicLong safe = new AtomicLong();
    private final AtomicLong review = new AtomicLong();
    private final AtomicLong blocked = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();

    public void recordRequest() { total.incrementAndGet(); }
    public void recordError() { errors.incrementAndGet(); }

    public void record(SafetyVerdict verdict) {
        switch (verdict) {
            case SAFE -> safe.incrementAndGet();
            case REVIEW -> review.incrementAndGet();
            case BLOCK -> blocked.incrementAndGet();
        }
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("safetyTotal", total.get());
        out.put("safetySafe", safe.get());
        out.put("safetyReview", review.get());
        out.put("safetyBlocked", blocked.get());
        out.put("safetyErrors", errors.get());
        return out;
    }
}
