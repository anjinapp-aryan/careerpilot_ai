package ai.careerpilot.applications.dto;

import ai.careerpilot.applications.ApplicationHealthService.HealthStatus;
import ai.careerpilot.applications.ApplicationRecommendationService.RecommendationAction;
import ai.careerpilot.domain.Application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Application Command Center DTOs. Records with a static {@code from(...)} factory, mirroring the
 * pattern in {@code ai.careerpilot.story.api.dto.StoryDtos} /
 * {@code ai.careerpilot.submission.api.dto.ApplicationSubmissionDtos} — entities never leave the
 * service layer.
 */
public final class ApplicationCardDtos {

    private ApplicationCardDtos() {}

    /**
     * The rich, read-time-assembled application card. A strict superset of the plain
     * {@link Application} entity fields (id/jobId/resumeId/status/matchScore/atsScore/nextAction/
     * nextActionAt/notes/createdAt/updatedAt) plus everything {@code ApplicationCardService} joins
     * in read-only. Served from the NEW {@code GET /api/applications/cards} and
     * {@code GET /api/applications/{id}} endpoints — the original {@code GET /api/applications}
     * response shape is untouched.
     */
    public record ApplicationCardResponse(
            UUID id,
            UUID jobId,
            UUID resumeId,
            String status,
            Integer matchScore,
            Integer atsScore,
            String nextAction,
            Instant nextActionAt,
            String notes,
            boolean favorite,
            String priority,
            boolean archived,
            Instant createdAt,
            Instant updatedAt,

            // Job fields (read-only join on Job by jobId)
            String jobTitle,
            String company,
            String location,
            String salaryRange,
            String remoteType,
            String source,
            String externalUrl,
            Boolean sponsorshipAvailable,
            // Guided Apply — the employer's own job identifier (Job.externalId), when the discovery
            // source recorded one. Null is honest for manually-added jobs, which have none.
            String employerJobId,

            // Artifact-presence signals (read-only joins; drive WorkflowStageChecklist)
            boolean resumeTailored,
            boolean atsAnalysisReady,
            boolean coverLetterReady,
            boolean applicationPackageReady,
            boolean applicationReviewReady,

            // Candidate signal (read-only, from CandidateProfile)
            Boolean visaRequired,

            // Deterministic computed signals
            HealthStatus healthStatus,
            int healthScore,
            String healthReasoning,
            RecommendationAction recommendationAction,
            String recommendationReasoning,
            String suggestedNextAction,
            Instant suggestedNextActionAt,

            // Phase 3A lifecycle status, when workflow.tracking.enabled and a lifecycle row exists
            String lifecycleStatus,

            // Phase 7.16.3 — Automation Recovery Center visibility, when application.execution.enabled
            // and an ApplicationExecution row exists for this (user, job). All null otherwise —
            // never fabricated. automationHealth is a derived display label (see ApplicationCardService),
            // not a persisted status, mirroring the WorkflowService#deriveDisplayStatus convention.
            UUID executionId,
            String executionStatus,
            String automationHealth,
            Integer retryCount,
            String verificationStatus,

            // Guided Apply — populated only when automationHealth is "GUIDED_APPLY_REQUIRED" (i.e.
            // the latest execution is ABORTED). blockerReason is the deterministic classification
            // (ai.careerpilot.domain.GuidedApplyReason); blockerDetail is the underlying raw
            // ApplicationExecution.failureReason text, for honest "why manual" copy the enum alone
            // can't carry. Both null otherwise — never fabricated.
            boolean guidedApplyRequired,
            String blockerReason,
            String blockerDetail
    ) {}

    public record BulkActionRequest(List<UUID> ids, String action, java.util.Map<String, String> payload) {}

    public record BulkActionResult(int requested, int applied, List<UUID> failedIds) {}
}
