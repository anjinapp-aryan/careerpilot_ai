package ai.careerpilot.memory.enterprise;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 11.4 — one stored memory. Immutable; {@link #accessed(Instant)} and {@link
 * #reclassified(MemoryType)} return a new instance, matching the record-with-wither convention
 * already used by {@code ai.careerpilot.mcp.McpToolResult} and friends.
 */
public record MemoryEntry(UUID id, UUID userId, MemoryType type, String content, MemoryImportance importance,
                           Instant createdAt, Instant lastAccessedAt, int accessCount, Set<String> tags) {

    public static MemoryEntry create(UUID userId, MemoryType type, String content, MemoryImportance importance, Set<String> tags) {
        Instant now = Instant.now();
        return new MemoryEntry(UUID.randomUUID(), userId, type, content, importance, now, now, 0, tags);
    }

    public MemoryEntry accessed(Instant at) {
        return new MemoryEntry(id, userId, type, content, importance, createdAt, at, accessCount + 1, tags);
    }

    public MemoryEntry reclassified(MemoryType newType) {
        return new MemoryEntry(id, userId, newType, content, importance, createdAt, lastAccessedAt, accessCount, tags);
    }
}
