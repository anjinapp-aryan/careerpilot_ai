package ai.careerpilot.resumetailoring.event;

import java.util.UUID;

/**
 * Phase 2D.5 — published by {@code CoverLetterService} once a cover letter version is persisted.
 * Consumer: {@code ApplicationPackageWorker} (Phase 2D.6).
 */
public record CoverLetterCompletedEvent(UUID userId, UUID jobId, UUID resumeTailoringId,
                                        UUID coverLetterId, int coverLetterVersion) {
}
