package ai.careerpilot.memory.enterprise;

import java.util.List;
import java.util.UUID;

/**
 * Phase 11.4 — free-text search across a user's stored memory, across all types. Rule-based
 * (substring) today — the deliberate embedding/ML-swap seam, same discipline as {@link
 * MemoryClassifier}.
 */
public interface MemorySearch {

    List<MemoryEntry> search(UUID userId, String query, int limit);
}
