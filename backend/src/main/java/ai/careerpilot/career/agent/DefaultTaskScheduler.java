package ai.careerpilot.career.agent;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 11.6 — the default {@link TaskScheduler}: an in-process, per-user last-run timestamp
 * map. Not persisted — a restart resets everyone's eligibility, which is an acceptable tradeoff
 * for a component nothing calls automatically yet (see the interface's own javadoc).
 */
public class DefaultTaskScheduler implements TaskScheduler {

    private final Map<UUID, Instant> lastRunAt = new ConcurrentHashMap<>();

    @Override
    public boolean isEligibleToRun(UUID userId, java.time.Duration minInterval) {
        Instant last = lastRunAt.get(userId);
        return last == null || last.isBefore(Instant.now().minus(minInterval));
    }

    @Override
    public void recordRun(UUID userId) {
        lastRunAt.put(userId, Instant.now());
    }
}
