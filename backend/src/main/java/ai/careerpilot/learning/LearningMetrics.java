package ai.careerpilot.learning;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Phase 6.1 — in-memory per-stage counters, exposed by the diagnostics controller. */
@Component
public class LearningMetrics {

    private final Map<String, AtomicLong> total = new LinkedHashMap<>();
    private final Map<String, AtomicLong> failures = new LinkedHashMap<>();

    public void recordSuccess(String stage) {
        total.computeIfAbsent(stage, k -> new AtomicLong()).incrementAndGet();
    }

    public void recordFailure(String stage) {
        total.computeIfAbsent(stage, k -> new AtomicLong()).incrementAndGet();
        failures.computeIfAbsent(stage, k -> new AtomicLong()).incrementAndGet();
    }

    public long total(String stage) {
        return total.getOrDefault(stage, new AtomicLong()).get();
    }

    public long failures(String stage) {
        return failures.getOrDefault(stage, new AtomicLong()).get();
    }

    public Map<String, Object> snapshot(String stage) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(stage + "Total", total(stage));
        out.put(stage + "Failures", failures(stage));
        return out;
    }
}
