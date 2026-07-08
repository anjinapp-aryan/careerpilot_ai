package ai.careerpilot.autopilot.orchestrator;

import ai.careerpilot.autopilot.decision.DecisionOutcome;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Phase 7.10 — in-memory counters for the orchestrator, exposed by the autopilot diagnostics endpoint. */
@Component
public class AutopilotMetrics {

    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();

    public void recordRun() { inc("runs"); }
    public void recordJobProcessed() { inc("jobsProcessed"); }
    public void recordDecision(DecisionOutcome outcome) { inc("decision." + outcome.name()); }
    public void recordAutoApplied() { inc("autoApplied"); }
    public void recordFailure() { inc("failures"); }

    public long get(String key) {
        AtomicLong v = counters.get(key);
        return v == null ? 0 : v.get();
    }

    public Map<String, Long> snapshot() {
        Map<String, Long> out = new java.util.LinkedHashMap<>();
        counters.forEach((k, v) -> out.put(k, v.get()));
        return out;
    }

    private void inc(String key) {
        counters.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
    }
}
