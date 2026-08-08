package ai.careerpilot.execution.timeline;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * P5 — per-stage counters and durations, and the "top failure stage / reason" answer.
 *
 * <p>Hand-rolled {@link AtomicLong} counters keyed by stage, matching the established style of
 * {@code ApplicationExecutionMetrics}, {@code BrowserPoolMetrics} and {@code AiMetrics} rather
 * than introducing a different metrics mechanism into a package family that already has three.
 *
 * <p><b>Bounded by construction.</b> Every map is keyed by an {@link ExecutionStage} or
 * {@link FailureCategory} name, so the key space is the enum — this cannot grow with traffic, which
 * is the property that makes it safe to keep in memory on a 1-vCPU box with no eviction.
 *
 * <p>These are process-lifetime counters and say so: they reset on restart. The durable answer to
 * the same questions lives in {@code execution_stage_event} and is served by the aggregate queries
 * on its repository — this exists for the cheap always-available snapshot, not as the system of
 * record.
 */
@Component
public class ExecutionStageMetrics {

    private final Map<String, AtomicLong> completed = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> failed = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> durationSumMs = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> durationCount = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> byCategory = new ConcurrentHashMap<>();

    /** Called by the recorder as each stage closes. Never throws. */
    public void record(String stage, String status, Long durationMs, String failureCategory) {
        if (stage == null || status == null) return;
        switch (status) {
            case "COMPLETED" -> bump(completed, stage);
            case "FAILED" -> {
                bump(failed, stage);
                bump(byCategory, failureCategory == null ? FailureCategory.UNKNOWN.name() : failureCategory);
            }
            default -> { /* SKIPPED is neither a success nor a failure and is not counted as either. */ }
        }
        if (durationMs != null && durationMs >= 0) {
            bump(durationSumMs, stage, durationMs);
            bump(durationCount, stage);
        }
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();

        Map<String, Object> averages = new LinkedHashMap<>();
        for (Map.Entry<String, AtomicLong> e : durationCount.entrySet()) {
            long count = e.getValue().get();
            if (count == 0) continue;
            long sum = durationSumMs.getOrDefault(e.getKey(), new AtomicLong()).get();
            averages.put(e.getKey(), sum / count);
        }
        out.put("averageStageDurationMs", averages);
        out.put("stageCompleted", flatten(completed));
        out.put("stageFailed", flatten(failed));
        out.put("failuresByCategory", flatten(byCategory));

        // The two questions an operator actually asks first. Null rather than a guess when nothing
        // has failed yet — "no failures observed" and "we don't know" must not look the same.
        out.put("topFailureStage", topKey(failed));
        out.put("topFailureCategory", topKey(byCategory));
        out.put("note", "process-lifetime counters; reset on restart. "
                + "The durable series is execution_stage_event.");
        return out;
    }

    private static String topKey(Map<String, AtomicLong> map) {
        return map.entrySet().stream()
                .max(Comparator.comparingLong(e -> e.getValue().get()))
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    private static Map<String, Long> flatten(Map<String, AtomicLong> map) {
        Map<String, Long> out = new LinkedHashMap<>();
        map.forEach((k, v) -> out.put(k, v.get()));
        return out;
    }

    private static void bump(Map<String, AtomicLong> map, String key) {
        bump(map, key, 1);
    }

    private static void bump(Map<String, AtomicLong> map, String key, long by) {
        if (key == null) return;
        map.computeIfAbsent(key, k -> new AtomicLong()).addAndGet(by);
    }
}
