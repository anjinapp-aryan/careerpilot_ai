package ai.careerpilot.packageintel.api.dto;

import ai.careerpilot.domain.ApplicationPackage;
import ai.careerpilot.domain.ApplicationPackageValidation;
import ai.careerpilot.domain.ApplicationPackageVersion;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Phase 7.11 — response DTOs for the application-package API. Controllers return these, never the JPA
 * entities directly (the project's DTO-for-JSON convention), so lineage ids and enrichment fields
 * serialize cleanly and no lazy graph leaks.
 */
public final class ApplicationPackageDtos {

    private ApplicationPackageDtos() {}

    public record PackageResponse(UUID id, UUID userId, UUID jobId, UUID applicationId,
                                  UUID resumeId, UUID resumeTailoringId, UUID coverLetterId,
                                  UUID atsAnalysisId, UUID gapAnalysisId, UUID atsExplanationId,
                                  UUID recommendationAuditId, UUID applicationDecisionId,
                                  Boolean companyResearchAvailable, Integer learningBoost,
                                  String recommendationStrength, String matchSummary, UUID correlationId,
                                  int packageVersion, String status, String validationStatus,
                                  String metadata, Instant createdAt, Instant updatedAt) {

        public static PackageResponse from(ApplicationPackage p) {
            return new PackageResponse(p.getId(), p.getUserId(), p.getJobId(), p.getApplicationId(),
                    p.getResumeId(), p.getResumeTailoringId(), p.getCoverLetterId(),
                    p.getAtsAnalysisId(), p.getGapAnalysisId(), p.getAtsExplanationId(),
                    p.getRecommendationAuditId(), p.getApplicationDecisionId(),
                    p.getCompanyResearchAvailable(), p.getLearningBoost(),
                    p.getRecommendationStrength(), p.getMatchSummary(), p.getCorrelationId(),
                    p.getPackageVersion() == null ? 0 : p.getPackageVersion(), p.getStatus(),
                    p.getValidationStatus(), p.getMetadata(), p.getCreatedAt(), p.getUpdatedAt());
        }
    }

    public record VersionResponse(UUID id, UUID applicationPackageId, int packageVersion, String status,
                                  UUID resumeId, UUID resumeTailoringId, UUID atsAnalysisId,
                                  String metadata, Instant createdAt) {

        public static VersionResponse from(ApplicationPackageVersion v) {
            return new VersionResponse(v.getId(), v.getApplicationPackageId(),
                    v.getPackageVersion() == null ? 0 : v.getPackageVersion(), v.getStatus(),
                    v.getResumeId(), v.getResumeTailoringId(), v.getAtsAnalysisId(),
                    v.getMetadata(), v.getCreatedAt());
        }
    }

    public record ValidationResponse(UUID id, UUID applicationPackageId, int packageVersion,
                                     String status, String blockingReason, String checks,
                                     UUID correlationId, Instant createdAt) {

        public static ValidationResponse from(ApplicationPackageValidation v) {
            return new ValidationResponse(v.getId(), v.getApplicationPackageId(),
                    v.getPackageVersion() == null ? 0 : v.getPackageVersion(), v.getStatus(),
                    v.getBlockingReason(), v.getChecks(), v.getCorrelationId(), v.getCreatedAt());
        }
    }

    /** Side-by-side of a package's current head and one historical version. */
    public record CompareResponse(PackageResponse current, VersionResponse against,
                                  List<String> changedArtifacts) {}
}
