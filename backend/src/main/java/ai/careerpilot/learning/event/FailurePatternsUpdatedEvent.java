package ai.careerpilot.learning.event;

import java.util.UUID;

/** Phase 6.3 — published after {@code FailurePatternEngine} recomputes patterns for a user. */
public record FailurePatternsUpdatedEvent(UUID correlationId, UUID userId) {
}
