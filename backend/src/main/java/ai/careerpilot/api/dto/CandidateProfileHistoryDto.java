package ai.careerpilot.api.dto;

import java.time.Instant;

/**
 * One audit entry from {@code /api/candidate-profile/history}. {@code before} is null for the
 * first generation. Both snapshots are full {@link CandidateProfileDto} views, supporting audit,
 * rollback, and explainability.
 */
public record CandidateProfileHistoryDto(
        String reason,
        Instant createdAt,
        CandidateProfileDto before,
        CandidateProfileDto after) {}
