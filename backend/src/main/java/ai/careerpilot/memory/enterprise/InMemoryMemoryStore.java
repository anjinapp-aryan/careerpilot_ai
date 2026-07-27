package ai.careerpilot.memory.enterprise;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 11.4 — the in-process, per-user store every other bean in this package shares (analogous
 * to {@code ai.careerpilot.mcp.tool.McpToolHandlerRegistry}'s role in Phase 10.2 — necessary
 * plumbing, not one of the phase spec's named types). Deliberately in-memory, not persisted —
 * per the phase's "ZERO database breaking changes" requirement, this evolution adds no new
 * table; a future phase that wants durability would replace this class, not the interfaces
 * built around it.
 */
public class InMemoryMemoryStore {

    private final Map<UUID, List<MemoryEntry>> byUser = new ConcurrentHashMap<>();

    public synchronized void add(MemoryEntry entry) {
        byUser.computeIfAbsent(entry.userId(), k -> new ArrayList<>()).add(entry);
    }

    public synchronized boolean remove(UUID userId, UUID memoryId) {
        List<MemoryEntry> entries = byUser.get(userId);
        return entries != null && entries.removeIf(e -> e.id().equals(memoryId));
    }

    public synchronized void replace(MemoryEntry updated) {
        List<MemoryEntry> entries = byUser.get(updated.userId());
        if (entries == null) return;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).id().equals(updated.id())) {
                entries.set(i, updated);
                return;
            }
        }
    }

    public synchronized List<MemoryEntry> allFor(UUID userId) {
        return List.copyOf(byUser.getOrDefault(userId, List.of()));
    }

    public synchronized List<MemoryEntry> allForType(UUID userId, MemoryType type) {
        return allFor(userId).stream().filter(e -> e.type() == type).toList();
    }
}
