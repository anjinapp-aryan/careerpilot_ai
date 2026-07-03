package ai.careerpilot.service.profile;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lightweight in-memory observability counters for Candidate Profile generation, mirroring
 * {@link ai.careerpilot.ai.AiMetrics}. Exposed via the diagnostics endpoint. No prompts,
 * resume content, or PII are ever recorded here — counts and latency only.
 */
@Component
public class CandidateProfileMetrics {

    private final AtomicLong generationCount = new AtomicLong();
    private final AtomicLong generationSuccess = new AtomicLong();
    private final AtomicLong generationFailure = new AtomicLong();
    private final AtomicLong latencySumMs = new AtomicLong();
    private final AtomicLong latencyCount = new AtomicLong();
    private final AtomicLong rebuildRequests = new AtomicLong();
    private final AtomicLong preferenceUpdates = new AtomicLong();

    /** A full AI extraction was attempted (resume changed or explicit rebuild). */
    public void recordGenerationAttempt() { generationCount.incrementAndGet(); }

    public void recordGenerationSuccess() { generationSuccess.incrementAndGet(); }

    public void recordGenerationFailure() { generationFailure.incrementAndGet(); }

    /** Accumulate a generation's wall-clock latency for the running average. */
    public void recordLatency(long ms) {
        latencySumMs.addAndGet(ms);
        latencyCount.incrementAndGet();
    }

    /** An explicit POST /rebuild was requested. */
    public void recordRebuildRequest() { rebuildRequests.incrementAndGet(); }

    /** A preferences-only re-merge ran (no LLM call). */
    public void recordPreferenceUpdate() { preferenceUpdates.incrementAndGet(); }

    public long generationCount() { return generationCount.get(); }

    public long avgLatencyMs() {
        long n = latencyCount.get();
        return n == 0 ? 0 : latencySumMs.get() / n;
    }

    /** Flat snapshot for the diagnostics endpoint. */
    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("profileGenerationCount", generationCount.get());
        out.put("profileGenerationSuccess", generationSuccess.get());
        out.put("profileGenerationFailure", generationFailure.get());
        out.put("profileGenerationAvgLatencyMs", avgLatencyMs());
        out.put("profileRebuildRequests", rebuildRequests.get());
        out.put("preferenceUpdates", preferenceUpdates.get());
        return out;
    }
}
