package ai.careerpilot.review;

import ai.careerpilot.packageintel.PackageValidationStatus;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Phase 7.12 — in-memory counters for the AI Review Pipeline, exposed by its diagnostics endpoint. */
@Component
public class ReviewMetrics {

    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private final AtomicLong totalReviewMs = new AtomicLong();
    private final AtomicLong totalQuality = new AtomicLong();
    private final AtomicLong qualitySamples = new AtomicLong();

    public void recordReview() { inc("reviews"); }
    public void recordVerdict(PackageValidationStatus verdict) { inc("verdict." + verdict.name()); }
    public void recordReviewerRun(String reviewer) { inc("reviewer." + reviewer); }
    public void recordFailure() { inc("failures"); }
    public void recordLatency(long ms) { totalReviewMs.addAndGet(ms); inc("latencySamples"); }
    public void recordQuality(int q) { totalQuality.addAndGet(q); qualitySamples.incrementAndGet(); }

    public long get(String key) {
        AtomicLong v = counters.get(key);
        return v == null ? 0 : v.get();
    }

    public long averageReviewMs() {
        long samples = get("latencySamples");
        return samples == 0 ? 0 : totalReviewMs.get() / samples;
    }

    public long averageQuality() {
        long s = qualitySamples.get();
        return s == 0 ? 0 : totalQuality.get() / s;
    }

    public Map<String, Long> snapshot() {
        Map<String, Long> out = new LinkedHashMap<>();
        counters.forEach((k, v) -> out.put(k, v.get()));
        out.put("averageReviewMs", averageReviewMs());
        out.put("averageQuality", averageQuality());
        return out;
    }

    private void inc(String key) {
        counters.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
    }
}
