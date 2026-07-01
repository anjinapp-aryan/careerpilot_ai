package ai.careerpilot.jobdiscovery.cache;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 2B-3 (Step 14) — in-memory hit/miss counters for {@link MatchCache}, mirroring
 * {@link ai.careerpilot.jobdiscovery.enrich.JobAiEnrichmentMetrics}. Exposed via the diagnostics
 * endpoint — counts only.
 */
@Component
public class MatchCacheMetrics {

    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    public void recordHit() { hits.incrementAndGet(); }

    public void recordMiss() { misses.incrementAndGet(); }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("matchCacheHits", hits.get());
        out.put("matchCacheMisses", misses.get());
        return out;
    }
}
