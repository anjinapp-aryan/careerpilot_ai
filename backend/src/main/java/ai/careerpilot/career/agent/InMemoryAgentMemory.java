package ai.careerpilot.career.agent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 11.6 — the default {@link AgentMemory}: an in-process, per-user bounded deque (cap
 * {@value #MAX_PER_USER}, oldest evicted first), same ring-buffer shape as every other bounded
 * history in this codebase ({@code InMemoryIntentHistory}, {@code InMemoryCareerTimeline}).
 */
public class InMemoryAgentMemory implements AgentMemory {

    private static final int MAX_PER_USER = 50;

    private final Map<UUID, Deque<AgentReflection>> history = new ConcurrentHashMap<>();

    @Override
    public synchronized void remember(AgentReflection reflection) {
        if (reflection == null || reflection.userId() == null) {
            return;
        }
        Deque<AgentReflection> deque = history.computeIfAbsent(reflection.userId(), k -> new ArrayDeque<>());
        deque.addFirst(reflection);
        while (deque.size() > MAX_PER_USER) {
            deque.removeLast();
        }
    }

    @Override
    public synchronized List<AgentReflection> recentFor(UUID userId, int limit) {
        Deque<AgentReflection> deque = history.getOrDefault(userId, new ArrayDeque<>());
        return deque.stream().limit(Math.max(0, limit)).toList();
    }
}
