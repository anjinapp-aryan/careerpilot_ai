package ai.careerpilot.story.metrics;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/** Phase 7.15 — in-memory counters for the STAR Story engine (same shape as {@code CompanyIntelMetrics}). */
@Component
public class StoryMetrics {

    private final ConcurrentHashMap<String, LongAdder> counters = new ConcurrentHashMap<>();

    public void increment(String key) {
        counters.computeIfAbsent(key, k -> new LongAdder()).increment();
    }

    public long get(String key) {
        LongAdder a = counters.get(key);
        return a == null ? 0 : a.sum();
    }

    public Map<String, Long> snapshot() {
        Map<String, Long> out = new LinkedHashMap<>();
        counters.forEach((k, v) -> out.put("counter." + k, v.sum()));
        return out;
    }
}
