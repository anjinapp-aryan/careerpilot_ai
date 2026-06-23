package ai.careerpilot.ai;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lightweight in-memory counters for AI Gateway observability. Tracks per-provider
 * call / success / failure counts, latency, and failure-class breakdowns (timeout,
 * rate-limit, circuit-open), plus the total number of failovers. Exposed via the
 * diagnostics endpoint. (No prompts or user data are ever recorded here.)
 */
@Component
public class AiMetrics {

    private final Map<String, AtomicLong> calls = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> successes = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> failures = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> timeouts = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> rateLimits = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> circuitOpens = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> latencySumMs = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> latencyCount = new ConcurrentHashMap<>();
    private final AtomicLong fallbacks = new AtomicLong();

    private static long inc(Map<String, AtomicLong> m, String key) {
        return m.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
    }

    private static long get(Map<String, AtomicLong> m, String key) {
        AtomicLong v = m.get(key);
        return v == null ? 0 : v.get();
    }

    public void recordCall(String provider) { inc(calls, provider); }

    public void recordSuccess(String provider) { inc(successes, provider); }

    public void recordFailure(String provider) { inc(failures, provider); }

    public void recordTimeout(String provider) { inc(timeouts, provider); }

    public void recordRateLimit(String provider) { inc(rateLimits, provider); }

    public void recordCircuitOpen(String provider) { inc(circuitOpens, provider); }

    /** Accumulate a successful call's wall-clock latency for the running average. */
    public void recordLatency(String provider, long ms) {
        latencySumMs.computeIfAbsent(provider, k -> new AtomicLong()).addAndGet(ms);
        inc(latencyCount, provider);
    }

    public void recordFallback() { fallbacks.incrementAndGet(); }

    public long calls(String provider) { return get(calls, provider); }

    public long failures(String provider) { return get(failures, provider); }

    public long fallbacks() { return fallbacks.get(); }

    /** Average successful-call latency in ms for a provider (0 if none recorded). */
    public long avgLatencyMs(String provider) {
        long n = get(latencyCount, provider);
        return n == 0 ? 0 : get(latencySumMs, provider) / n;
    }

    /**
     * Flat snapshot per provider plus the global fallback count, e.g.
     * {deepseekCalls, deepseekSuccesses, deepseekFailures, deepseekTimeouts,
     *  deepseekRateLimits, deepseekCircuitOpens, deepseekAvgLatencyMs, …, fallbackCount}.
     */
    public Map<String, Object> snapshot(Iterable<String> providerKeys) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : providerKeys) {
            out.put(key + "Calls", calls(key));
            out.put(key + "Successes", get(successes, key));
            out.put(key + "Failures", failures(key));
            out.put(key + "Timeouts", get(timeouts, key));
            out.put(key + "RateLimits", get(rateLimits, key));
            out.put(key + "CircuitOpens", get(circuitOpens, key));
            out.put(key + "AvgLatencyMs", avgLatencyMs(key));
        }
        out.put("fallbackCount", fallbacks());
        return out;
    }
}
