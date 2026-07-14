package ai.careerpilot.submission.api.dto;

import ai.careerpilot.domain.ApplicationSubmissionAnswer;
import ai.careerpilot.domain.ApplicationSubmissionSession;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Phase 7.16 — response DTOs for the Application Submission API (entities never leave the service layer). */
public final class ApplicationSubmissionDtos {

    private ApplicationSubmissionDtos() {}

    public record SessionResponse(UUID id, UUID userId, UUID jobId, UUID applicationId, UUID resumeId,
                                  UUID resumeTailoringId, UUID applicationPackageId, UUID applicationReviewId,
                                  UUID companyKnowledgeId, UUID approvalQueueEntryId, UUID applicationExecutionId,
                                  String status, String submissionMethod, String provider, UUID correlationId,
                                  String failureReason, Instant startedAt, Instant completedAt,
                                  Instant createdAt, Instant updatedAt) {
        public static SessionResponse from(ApplicationSubmissionSession s) {
            return new SessionResponse(s.getId(), s.getUserId(), s.getJobId(), s.getApplicationId(), s.getResumeId(),
                    s.getResumeTailoringId(), s.getApplicationPackageId(), s.getApplicationReviewId(),
                    s.getCompanyKnowledgeId(), s.getApprovalQueueEntryId(), s.getApplicationExecutionId(),
                    s.getStatus(), s.getSubmissionMethod(), s.getProvider(), s.getCorrelationId(),
                    s.getFailureReason(), s.getStartedAt(), s.getCompletedAt(), s.getCreatedAt(), s.getUpdatedAt());
        }
    }

    public record StatusResponse(UUID id, String status, String provider, String failureReason, Instant updatedAt) {
        public static StatusResponse from(ApplicationSubmissionSession s) {
            return new StatusResponse(s.getId(), s.getStatus(), s.getProvider(), s.getFailureReason(), s.getUpdatedAt());
        }
    }

    public record AnswerResponse(UUID id, String questionText, String questionCategory, String answerText,
                                 String sourceRefs, Instant createdAt) {
        public static AnswerResponse from(ApplicationSubmissionAnswer a) {
            return new AnswerResponse(a.getId(), a.getQuestionText(), a.getQuestionCategory(), a.getAnswerText(),
                    a.getSourceRefs(), a.getCreatedAt());
        }
    }

    public record SessionDetailResponse(SessionResponse session, List<AnswerResponse> answers) {
        public static SessionDetailResponse from(ApplicationSubmissionSession s, List<ApplicationSubmissionAnswer> a) {
            return new SessionDetailResponse(SessionResponse.from(s), a.stream().map(AnswerResponse::from).toList());
        }
    }

    public record StartRequest(UUID jobId, UUID resumeId) {}
}
