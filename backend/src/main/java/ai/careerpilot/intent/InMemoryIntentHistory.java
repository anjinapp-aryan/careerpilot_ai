package ai.careerpilot.intent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 11.1 — the default {@link IntentHistory}: an in-process, per-user bounded deque (cap
 * {@value #MAX_PER_USER} entries, oldest evicted first) — a simple ring-buffer, not a persisted
 * ledger. If a future phase needs durable history, it should replace this implementation, not
 * this interface.
 */
public class InMemoryIntentHistory implements IntentHistory {

    private static final int MAX_PER_USER = 50;

    private final Map<UUID, Deque<IntentResult>> history = new ConcurrentHashMap<>();

    @Override
    public synchronized void record(UUID userId, IntentResult result) {
        Deque<IntentResult> deque = history.computeIfAbsent(userId, k -> new ArrayDeque<>());
        deque.addFirst(result);
        while (deque.size() > MAX_PER_USER) {
            deque.removeLast();
        }
    }

    @Override
    public synchronized List<IntentResult> recentFor(UUID userId, int limit) {
        Deque<IntentResult> deque = history.getOrDefault(userId, new ArrayDeque<>());
        return deque.stream().limit(Math.max(0, limit)).toList();
    }
}
