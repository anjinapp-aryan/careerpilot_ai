package ai.careerpilot.jobdiscovery.enrich;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lightweight in-memory observability counters for LLM job enrichment, mirroring
 * {@link ai.careerpilot.service.profile.CandidateProfileMetrics} and {@code AiMetrics}.
 * Exposed via the diagnostics endpoint — counts and latency only, no posting content.
 */
@Component
public class JobAiEnrichmentMetrics {

    private final AtomicLong attempts = new AtomicLong();
    private final AtomicLong success = new AtomicLong();
    private final AtomicLong failure = new AtomicLong();
    private final AtomicLong latencySumMs = new AtomicLong();
    private final AtomicLong latencyCount = new AtomicLong();
    private final AtomicLong batchRuns = new AtomicLong();
    private final AtomicLong singleRequests = new AtomicLong();

    /** One job enrichment was attempted (LLM call made). */
    public void recordAttempt() { attempts.incrementAndGet(); }

    public void recordSuccess() { success.incrementAndGet(); }

    public void recordFailure() { failure.incrementAndGet(); }

    /** Accumulate a single enrichment's wall-clock latency for the running average. */
    public void recordLatency(long ms) {
        latencySumMs.addAndGet(ms);
        latencyCount.incrementAndGet();
    }

    /** A capped batch pass ran (scheduler or backfill endpoint). */
    public void recordBatchRun() { batchRuns.incrementAndGet(); }

    /** A single-job on-demand enrichment was requested. */
    public void recordSingleRequest() { singleRequests.incrementAndGet(); }

    public long avgLatencyMs() {
        long n = latencyCount.get();
        return n == 0 ? 0 : latencySumMs.get() / n;
    }

    /** Flat snapshot for the diagnostics endpoint. */
    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enrichmentAttempts", attempts.get());
        out.put("enrichmentSuccess", success.get());
        out.put("enrichmentFailure", failure.get());
        out.put("enrichmentAvgLatencyMs", avgLatencyMs());
        out.put("enrichmentBatchRuns", batchRuns.get());
        out.put("enrichmentSingleRequests", singleRequests.get());
        return out;
    }
}
