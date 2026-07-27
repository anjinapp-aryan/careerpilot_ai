package ai.careerpilot.memory.enterprise;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 11.4 — the default {@link MemoryMetrics}, same hand-rolled {@link AtomicLong} counter
 * style as every other metrics implementation in this codebase.
 */
public class InMemoryMemoryMetrics implements MemoryMetrics {

    private final Map<String, AtomicLong> remembers = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> forgets = new ConcurrentHashMap<>();
    private final AtomicLong retrievalLatencySumMs = new AtomicLong();
    private final AtomicLong retrievalSamples = new AtomicLong();
    private final AtomicLong searchLatencySumMs = new AtomicLong();
    private final AtomicLong searchSamples = new AtomicLong();
    private final AtomicLong totalPromoted = new AtomicLong();
    private final AtomicLong totalEvicted = new AtomicLong();

    @Override
    public void recordRemember(String type) {
        remembers.computeIfAbsent(type, k -> new AtomicLong()).incrementAndGet();
    }

    @Override
    public void recordForget(String type) {
        forgets.computeIfAbsent(type, k -> new AtomicLong()).incrementAndGet();
    }

    @Override
    public void recordRetrieval(String type, long latencyMs) {
        retrievalLatencySumMs.addAndGet(latencyMs);
        retrievalSamples.incrementAndGet();
    }

    @Override
    public void recordSearch(long latencyMs, int resultCount) {
        searchLatencySumMs.addAndGet(latencyMs);
        searchSamples.incrementAndGet();
    }

    @Override
    public void recordConsolidation(int promoted, int evicted) {
        totalPromoted.addAndGet(promoted);
        totalEvicted.addAndGet(evicted);
    }

    public long rememberCount(String type) {
        return remembers.getOrDefault(type, new AtomicLong()).get();
    }

    public long forgetCount(String type) {
        return forgets.getOrDefault(type, new AtomicLong()).get();
    }

    public long avgRetrievalLatencyMs() {
        long samples = retrievalSamples.get();
        return samples == 0 ? 0 : retrievalLatencySumMs.get() / samples;
    }

    public long avgSearchLatencyMs() {
        long samples = searchSamples.get();
        return samples == 0 ? 0 : searchLatencySumMs.get() / samples;
    }

    public long totalPromoted() {
        return totalPromoted.get();
    }

    public long totalEvicted() {
        return totalEvicted.get();
    }
}
