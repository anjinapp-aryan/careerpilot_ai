package ai.careerpilot.autopilot.provider;

import java.util.UUID;

/** Phase 7.4 — everything a provider needs to attempt one submission. Read-only. */
public record ApplicationSubmissionRequest(UUID userId, UUID jobId, String applicationUrl, UUID resumeTailoringId) {
}
