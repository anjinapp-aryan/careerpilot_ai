package ai.careerpilot.memory.enterprise;

import java.util.List;
import java.util.UUID;

/**
 * Phase 11.4 — the primary facade for the enterprise memory layer: remember, forget, and list.
 * Deliberately separate from {@code ai.careerpilot.memory.CareerMemoryService} (Phase 7.15.1) —
 * that service remains the system of record for career-decision memory specifically; this
 * manager is the new, broader classification layer sitting alongside it, not replacing it.
 */
public interface MemoryManager {

    /** {@code typeHint} may be {@code null} — the entry is then classified automatically. */
    MemoryEntry remember(UUID userId, String content, MemoryType typeHint);

    boolean forget(UUID userId, UUID memoryId);

    List<MemoryEntry> allFor(UUID userId, MemoryType type);
}
