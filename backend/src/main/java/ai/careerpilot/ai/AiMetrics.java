package ai.careerpilot.ai;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lightweight in-memory counters for AI Gateway observability. Tracks per-provider
 * call/failure counts and the total number of failovers. Exposed via /api/ai/stats.
 * (No prompts or user data are ever recorded here.)
 */
@Component
public class AiMetrics {

    private final Map<String, AtomicLong> calls = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> failures = new ConcurrentHashMap<>();
    private final AtomicLong fallbacks = new AtomicLong();

    public void recordCall(String provider) {
        calls.computeIfAbsent(provider, k -> new AtomicLong()).incrementAndGet();
    }

    public void recordFailure(String provider) {
        failures.computeIfAbsent(provider, k -> new AtomicLong()).incrementAndGet();
    }

    public void recordFallback() {
        fallbacks.incrementAndGet();
    }

    public long calls(String provider) {
        AtomicLong c = calls.get(provider);
        return c == null ? 0 : c.get();
    }

    public long failures(String provider) {
        AtomicLong f = failures.get(provider);
        return f == null ? 0 : f.get();
    }

    public long fallbacks() {
        return fallbacks.get();
    }

    /** Flat snapshot, e.g. {geminiCalls, geminiFailures, deepseekCalls, …, fallbackCount}. */
    public Map<String, Object> snapshot(Iterable<String> providerKeys) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : providerKeys) {
            out.put(key + "Calls", calls(key));
            out.put(key + "Failures", failures(key));
        }
        out.put("fallbackCount", fallbacks());
        return out;
    }
}
