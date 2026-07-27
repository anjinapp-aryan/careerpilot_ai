package ai.careerpilot.memory.enterprise;

import java.util.List;
import java.util.UUID;

/** Phase 11.4 — ranked retrieval of stored memory, marking each returned entry as accessed. */
public interface MemoryRetriever {

    List<MemoryEntry> retrieve(UUID userId, MemoryType type, int limit);
}
