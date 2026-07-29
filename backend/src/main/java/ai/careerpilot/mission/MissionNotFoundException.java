package ai.careerpilot.mission;

import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * Mission Engine, Phase 1 — thrown when a mission doesn't exist, or exists but belongs to a
 * different user. Deliberately doesn't distinguish the two cases in its response (both map to
 * {@code 404} via the existing {@code GlobalExceptionHandler}'s {@link NoSuchElementException}
 * handler) — that's a security-conscious choice, not a limitation: telling an attacker probing
 * IDs "this exists but isn't yours" (403) leaks more than a flat "not found" (404).
 */
public class MissionNotFoundException extends NoSuchElementException {
    public MissionNotFoundException(UUID missionId) {
        super("Mission not found: " + missionId);
    }
}
