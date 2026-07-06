package ai.careerpilot.dailydiscovery;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Phase 5 — in-memory counters for the daily discovery pipeline, exposed by the diagnostics controller. */
@Component
public class DailyJobDiscoveryMetrics {

    private final AtomicLong totalRuns = new AtomicLong();
    private final AtomicLong successfulRuns = new AtomicLong();
    private final AtomicLong failedRuns = new AtomicLong();
    private final AtomicLong usersProcessed = new AtomicLong();
    private final AtomicReference<Instant> lastRunAt = new AtomicReference<>();
    private final AtomicReference<Instant> lastSuccessAt = new AtomicReference<>();
    private final AtomicReference<String> lastStatus = new AtomicReference<>("NEVER_RUN");
    private final AtomicLong lastDurationMs = new AtomicLong();

    public void recordRunStart() {
        totalRuns.incrementAndGet();
        lastRunAt.set(Instant.now());
    }

    public void recordRunFinished(String status, long durationMs, int usersProcessedThisRun) {
        lastStatus.set(status);
        lastDurationMs.set(durationMs);
        usersProcessed.addAndGet(usersProcessedThisRun);
        if ("SUCCESS".equals(status)) {
            successfulRuns.incrementAndGet();
            lastSuccessAt.set(Instant.now());
        } else if ("FAILED".equals(status)) {
            failedRuns.incrementAndGet();
        }
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalRuns", totalRuns.get());
        out.put("successfulRuns", successfulRuns.get());
        out.put("failedRuns", failedRuns.get());
        out.put("usersProcessed", usersProcessed.get());
        out.put("lastRunAt", lastRunAt.get());
        out.put("lastSuccessAt", lastSuccessAt.get());
        out.put("lastStatus", lastStatus.get());
        out.put("lastDurationMs", lastDurationMs.get());
        return out;
    }
}
