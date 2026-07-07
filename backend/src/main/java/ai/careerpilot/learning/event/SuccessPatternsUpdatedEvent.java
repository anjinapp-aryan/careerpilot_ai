package ai.careerpilot.learning.event;

import java.util.UUID;

/** Phase 6.2 — published after {@code SuccessPatternEngine} recomputes patterns for a user. */
public record SuccessPatternsUpdatedEvent(UUID correlationId, UUID userId) {
}
