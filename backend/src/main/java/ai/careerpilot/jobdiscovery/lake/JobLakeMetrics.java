package ai.careerpilot.jobdiscovery.lake;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory observability for the Job Lake pipeline (Phase 2A Step 11), mirroring
 * {@link ai.careerpilot.jobdiscovery.enrich.JobAiEnrichmentMetrics}. Exposes the spec's metrics —
 * {@code jobs_discovered_total}, {@code jobs_normalized_total}, {@code jobs_deduplicated_total},
 * {@code jobs_failed_total}, plus per-provider latency and success rate. Counts and latency only,
 * never posting content; cardinality is bounded to the fixed provider set.
 */
@Component
public class JobLakeMetrics {

    private final AtomicLong discoveredTotal = new AtomicLong();   // raw listings seen
    private final AtomicLong normalizedTotal = new AtomicLong();   // promoted into the lake
    private final AtomicLong deduplicatedTotal = new AtomicLong(); // mapped onto another canonical
    private final AtomicLong failedTotal = new AtomicLong();       // provider failures

    private final Map<String, AtomicLong> providerLatencySumMs = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> providerLatencyCount = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> providerSuccessRuns = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> providerTotalRuns = new ConcurrentHashMap<>();

    public void recordProviderSuccess(String provider, int found, int inserted, int duplicates, long nanos) {
        discoveredTotal.addAndGet(found);
        normalizedTotal.addAndGet(inserted);
        deduplicatedTotal.addAndGet(duplicates);
        counter(providerLatencySumMs, provider).addAndGet(nanos / 1_000_000);
        counter(providerLatencyCount, provider).incrementAndGet();
        counter(providerSuccessRuns, provider).incrementAndGet();
        counter(providerTotalRuns, provider).incrementAndGet();
    }

    public void recordProviderFailure(String provider, long nanos) {
        failedTotal.incrementAndGet();
        counter(providerLatencySumMs, provider).addAndGet(nanos / 1_000_000);
        counter(providerLatencyCount, provider).incrementAndGet();
        counter(providerTotalRuns, provider).incrementAndGet();
    }

    /** Flat snapshot for the diagnostics / discovery-status endpoint. */
    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("jobs_discovered_total", discoveredTotal.get());
        out.put("jobs_normalized_total", normalizedTotal.get());
        out.put("jobs_deduplicated_total", deduplicatedTotal.get());
        out.put("jobs_failed_total", failedTotal.get());

        Map<String, Object> latency = new LinkedHashMap<>();
        Map<String, Object> successRate = new LinkedHashMap<>();
        for (String p : providerTotalRuns.keySet()) {
            latency.put(p, avgLatencyMs(p));
            long total = counter(providerTotalRuns, p).get();
            long ok = counter(providerSuccessRuns, p).get();
            successRate.put(p, total == 0 ? 0.0 : (double) ok / total);
        }
        out.put("provider_latency_ms", latency);
        out.put("provider_success_rate", successRate);
        return out;
    }

    private long avgLatencyMs(String provider) {
        long n = counter(providerLatencyCount, provider).get();
        return n == 0 ? 0 : counter(providerLatencySumMs, provider).get() / n;
    }

    private static AtomicLong counter(Map<String, AtomicLong> map, String key) {
        return map.computeIfAbsent(key, k -> new AtomicLong());
    }
}
