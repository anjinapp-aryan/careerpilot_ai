package ai.careerpilot.review.api.dto;

import ai.careerpilot.domain.ApplicationReview;
import ai.careerpilot.domain.ApplicationReviewHistory;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 7.12 — response DTOs for the application-review API. Controllers return these, never the JPA
 * entities directly (the project's DTO-for-JSON convention).
 */
public final class ApplicationReviewDtos {

    private ApplicationReviewDtos() {}

    public record ReviewResponse(UUID id, UUID applicationPackageId, UUID userId, UUID jobId,
                                 int packageVersion, Integer resumeScore, Integer atsScore,
                                 Integer companyFitScore, Integer learningConfidence, String consistencyStatus,
                                 Integer qualityScore, String qualityCategory, String verdict, Integer confidence,
                                 String reasons, UUID correlationId, int reviewVersion,
                                 Instant createdAt, Instant updatedAt) {

        public static ReviewResponse from(ApplicationReview r) {
            return new ReviewResponse(r.getId(), r.getApplicationPackageId(), r.getUserId(), r.getJobId(),
                    r.getPackageVersion() == null ? 0 : r.getPackageVersion(), r.getResumeScore(), r.getAtsScore(),
                    r.getCompanyFitScore(), r.getLearningConfidence(), r.getConsistencyStatus(),
                    r.getQualityScore(), r.getQualityCategory(), r.getVerdict(), r.getConfidence(),
                    r.getReasons(), r.getCorrelationId(), r.getReviewVersion() == null ? 0 : r.getReviewVersion(),
                    r.getCreatedAt(), r.getUpdatedAt());
        }
    }

    public record ReviewHistoryResponse(UUID id, UUID applicationReviewId, int packageVersion, int reviewVersion,
                                        Integer qualityScore, String qualityCategory, String verdict,
                                        Integer confidence, String consistencyStatus, Instant createdAt) {

        public static ReviewHistoryResponse from(ApplicationReviewHistory h) {
            return new ReviewHistoryResponse(h.getId(), h.getApplicationReviewId(),
                    h.getPackageVersion() == null ? 0 : h.getPackageVersion(),
                    h.getReviewVersion() == null ? 0 : h.getReviewVersion(),
                    h.getQualityScore(), h.getQualityCategory(), h.getVerdict(), h.getConfidence(),
                    h.getConsistencyStatus(), h.getCreatedAt());
        }
    }

    public record QualityResponse(UUID applicationPackageId, Integer qualityScore, String qualityCategory,
                                  String verdict, Integer confidence) {

        public static QualityResponse from(ApplicationReview r) {
            return new QualityResponse(r.getApplicationPackageId(), r.getQualityScore(), r.getQualityCategory(),
                    r.getVerdict(), r.getConfidence());
        }
    }
}
