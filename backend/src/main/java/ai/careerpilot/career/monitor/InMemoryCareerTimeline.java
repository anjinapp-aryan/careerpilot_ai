package ai.careerpilot.career.monitor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 11.5 — the default {@link CareerTimeline}: an in-process, per-user bounded deque (cap
 * {@value #MAX_PER_USER}, oldest evicted first), same ring-buffer shape as {@code
 * ai.careerpilot.intent.InMemoryIntentHistory}. Not persisted — a future phase wanting durable
 * insight history would replace this class, not the interface.
 */
public class InMemoryCareerTimeline implements CareerTimeline {

    private static final int MAX_PER_USER = 100;

    private final Map<UUID, Deque<CareerAlert>> history = new ConcurrentHashMap<>();

    @Override
    public synchronized void record(CareerAlert alert) {
        Deque<CareerAlert> deque = history.computeIfAbsent(alert.userId(), k -> new ArrayDeque<>());
        deque.addFirst(alert);
        while (deque.size() > MAX_PER_USER) {
            deque.removeLast();
        }
    }

    @Override
    public synchronized boolean wasRecentlySurfaced(UUID userId, CareerAlertType type, Duration cooldown) {
        Instant cutoff = Instant.now().minus(cooldown);
        Deque<CareerAlert> deque = history.getOrDefault(userId, new ArrayDeque<>());
        return deque.stream().anyMatch(a -> a.type() == type && a.detectedAt().isAfter(cutoff));
    }

    @Override
    public synchronized List<CareerAlert> recentFor(UUID userId, int limit) {
        Deque<CareerAlert> deque = history.getOrDefault(userId, new ArrayDeque<>());
        return deque.stream().limit(Math.max(0, limit)).toList();
    }
}
