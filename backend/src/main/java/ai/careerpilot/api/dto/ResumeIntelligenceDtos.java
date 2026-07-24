package ai.careerpilot.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Phase 8.2 — Resume Intelligence Center wire shapes. Deliberately separate from
 * {@link CandidateProfileDto} (the canonical per-user profile) — these describe the
 * per-resume analysis *lifecycle*, not the extracted intelligence itself.
 */
public class ResumeIntelligenceDtos {

    private ResumeIntelligenceDtos() {}

    /**
     * {@code status} is one of NOT_ANALYZED | ANALYZING | ANALYZED | OUTDATED | FAILED | PARTIAL.
     * {@code atsScore} is null unless a LangGraph AI Workflow run exists for this exact resume —
     * there is no honest job-less ATS score to show for a bare upload (see
     * ResumeIntelligenceCenterService for why).
     */
    public record ResumeAnalysisStatusDto(
            UUID resumeId,
            String status,
            Instant lastAnalysisDate,
            Long analysisDurationMs,
            BigDecimal confidenceScore,
            Integer atsScore,
            String errorMessage) {}

    public record ResumeDashboardEntryDto(
            UUID resumeId,
            String filename,
            Long sizeBytes,
            Instant uploadDate,
            ResumeAnalysisStatusDto analysis) {}

    /** One entry in a resume's analysis history, reusing {@link CandidateProfileHistoryDto}'s shape. */
    public record ResumeAnalysisHistoryEntryDto(
            String reason,
            Instant createdAt,
            CandidateProfileDto before,
            CandidateProfileDto after) {}
}
