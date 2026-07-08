package ai.careerpilot.packageintel;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Phase 7.11 — in-memory counters for the package intelligence layer, exposed by its diagnostics endpoint. */
@Component
public class PackageIntelligenceMetrics {

    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private final AtomicLong totalGenerationMs = new AtomicLong();

    public void recordGenerated() { inc("generated"); }
    public void recordValidation(PackageValidationStatus status) { inc("validation." + status.name()); }
    public void recordFailure() { inc("failures"); }
    public void recordLatency(long ms) { totalGenerationMs.addAndGet(ms); inc("latencySamples"); }

    public long get(String key) {
        AtomicLong v = counters.get(key);
        return v == null ? 0 : v.get();
    }

    public long averageGenerationMs() {
        long samples = get("latencySamples");
        return samples == 0 ? 0 : totalGenerationMs.get() / samples;
    }

    public Map<String, Long> snapshot() {
        Map<String, Long> out = new LinkedHashMap<>();
        counters.forEach((k, v) -> out.put(k, v.get()));
        out.put("averageGenerationMs", averageGenerationMs());
        return out;
    }

    private void inc(String key) {
        counters.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
    }
}
