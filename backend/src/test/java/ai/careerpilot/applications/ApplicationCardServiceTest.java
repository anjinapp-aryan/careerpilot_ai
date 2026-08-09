package ai.careerpilot.applications;

import ai.careerpilot.applications.dto.ApplicationCardDtos.ApplicationCardResponse;
import ai.careerpilot.domain.Application;
import ai.careerpilot.domain.ApplicationExecution;
import ai.careerpilot.domain.Job;
import ai.careerpilot.repo.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DTO-assembly coverage for {@link ApplicationCardService} — verifies the joined Job fields land on
 * the card, that a missing job doesn't throw, and that the deterministic health/recommendation/
 * next-action engines are always populated (never null) regardless of how sparse the joined data is.
 */
class ApplicationCardServiceTest {

    private final JobRepository jobs = mock(JobRepository.class);
    private final JobRecommendationRepository jobRecommendations = mock(JobRecommendationRepository.class);
    private final ResumeTailoringRepository resumeTailorings = mock(ResumeTailoringRepository.class);
    private final ResumeAtsAnalysisRepository atsAnalyses = mock(ResumeAtsAnalysisRepository.class);
    private final CoverLetterRepository coverLetters = mock(CoverLetterRepository.class);
    private final ApplicationPackageRepository applicationPackages = mock(ApplicationPackageRepository.class);
    private final ApplicationReviewRepository applicationReviews = mock(ApplicationReviewRepository.class);
    private final CandidateProfileRepository candidateProfiles = mock(CandidateProfileRepository.class);
    private final ApplicationLifecycleRepository lifecycles = mock(ApplicationLifecycleRepository.class);
    private final ApplicationStatusHistoryRepository statusHistory = mock(ApplicationStatusHistoryRepository.class);
    private final ApplicationExecutionRepository executions = mock(ApplicationExecutionRepository.class);
    private final ApplicationRetryRepository retries = mock(ApplicationRetryRepository.class);
    private final ApplicationSubmissionSessionRepository submissionSessions = mock(ApplicationSubmissionSessionRepository.class);

    private ApplicationCardService svc;
    private UUID userId;
    private UUID jobId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        jobId = UUID.randomUUID();
        svc = new ApplicationCardService(jobs, jobRecommendations, resumeTailorings, atsAnalyses, coverLetters,
                applicationPackages, applicationReviews, candidateProfiles, lifecycles, statusHistory,
                new ApplicationHealthService(), new ApplicationRecommendationService(), new ApplicationNextActionService(),
                executions, retries, submissionSessions);

        // The service prefetches every join in bulk (one query each, not one per application), so the
        // empty baseline is stubbed on the bulk finders.
        when(jobs.findAllById(any())).thenReturn(List.of());
        when(jobRecommendations.findByUserIdAndJobIdIn(any(), any())).thenReturn(List.of());
        when(resumeTailorings.findTailoredJobIds(any(), any())).thenReturn(List.of());
        when(atsAnalyses.findLatestScoresByJob(any(), any())).thenReturn(List.of());
        when(coverLetters.findJobIdsWithCoverLetter(any(), any())).thenReturn(List.of());
        when(applicationPackages.findRefsByUserIdAndJobIdIn(any(), any())).thenReturn(List.of());
        when(candidateProfiles.findByUserId(any())).thenReturn(Optional.empty());
        when(lifecycles.findByUserId(any())).thenReturn(List.of());
        when(executions.findLatestPerJob(any(), any())).thenReturn(List.of());
        when(submissionSessions.findByUserIdOrderByCreatedAtDesc(any())).thenReturn(List.of());
    }

    /** Stubs the latest-execution join for {@code jobId} and, when non-null, its retry count. */
    private void stubExecution(ApplicationExecution exec, Long retryCount) {
        when(executions.findLatestPerJob(any(), any())).thenReturn(List.of(exec));
        if (retryCount != null) {
            ApplicationRetryRepository.RetryCount row = mock(ApplicationRetryRepository.RetryCount.class);
            when(row.getExecutionId()).thenReturn(exec.getId());
            when(row.getCnt()).thenReturn(retryCount);
            when(retries.countPerExecution(any())).thenReturn(List.of(row));
        } else {
            when(retries.countPerExecution(any())).thenReturn(List.of());
        }
    }

    private Application app() {
        return Application.builder()
                .id(UUID.randomUUID()).userId(userId).orgId(UUID.randomUUID()).jobId(jobId)
                .status("APPLIED").favorite(false).priority("MEDIUM").archived(false)
                .createdAt(Instant.now()).updatedAt(Instant.now())
                .build();
    }

    @Test
    void joinsJobFieldsWhenPresent() {
        when(jobs.findAllById(any())).thenReturn(List.of(Job.builder()
                .id(jobId).title("Backend Engineer").company("Acme").location("Remote")
                .description("desc").build()));

        ApplicationCardResponse card = svc.assemble(userId, app());

        assertThat(card.jobTitle()).isEqualTo("Backend Engineer");
        assertThat(card.company()).isEqualTo("Acme");
        assertThat(card.location()).isEqualTo("Remote");
    }

    @Test
    void missingJobDoesNotThrow() {

        ApplicationCardResponse card = svc.assemble(userId, app());

        assertThat(card.jobTitle()).isNull();
        assertThat(card.company()).isNull();
    }

    @Test
    void healthRecommendationAndNextActionAreAlwaysPopulated() {

        ApplicationCardResponse card = svc.assemble(userId, app());

        assertThat(card.healthStatus()).isNotNull();
        assertThat(card.recommendationAction()).isNotNull();
        assertThat(card.suggestedNextAction()).isNotBlank();
    }

    @Test
    void assembleAllProducesOneCardPerApplication() {
        List<ApplicationCardResponse> results = svc.assembleAll(userId, List.of(app(), app()));
        assertThat(results).hasSize(2);
    }

    @Test
    void assembleAllQueriesOncePerJoinRegardlessOfHowManyApplications() {
        // The defect this pins: assembleAll used to call assemble() per row, and assemble() issues
        // ~12 lookups. Against the remote database that is ~12 round-trips per application — a real
        // 20-application account made GET /api/applications/cards hang past every client timeout.
        // Each join must therefore be queried exactly ONCE no matter how long the list is.
        List<Application> twenty = java.util.stream.IntStream.range(0, 20)
                .mapToObj(i -> Application.builder()
                        .id(UUID.randomUUID()).userId(userId).orgId(UUID.randomUUID())
                        .jobId(UUID.randomUUID()).status("APPLIED")
                        .createdAt(Instant.now()).updatedAt(Instant.now()).build())
                .toList();

        assertThat(svc.assembleAll(userId, twenty)).hasSize(20);

        verify(jobs, times(1)).findAllById(any());
        verify(jobRecommendations, times(1)).findByUserIdAndJobIdIn(any(), any());
        verify(resumeTailorings, times(1)).findTailoredJobIds(any(), any());
        verify(atsAnalyses, times(1)).findLatestScoresByJob(any(), any());
        verify(coverLetters, times(1)).findJobIdsWithCoverLetter(any(), any());
        verify(applicationPackages, times(1)).findRefsByUserIdAndJobIdIn(any(), any());
        verify(candidateProfiles, times(1)).findByUserId(any());
        verify(lifecycles, times(1)).findByUserId(any());
        verify(executions, times(1)).findLatestPerJob(any(), any());
        verify(submissionSessions, times(1)).findByUserIdOrderByCreatedAtDesc(any());

        // The per-row finders must not be reachable from the list path at all.
        verify(jobs, never()).findById(any());
        verify(jobRecommendations, never()).findByUserIdAndJobId(any(), any());
        verify(atsAnalyses, never()).findFirstByUserIdAndJobIdOrderByCreatedAtDesc(any(), any());
        verify(executions, never()).findFirstByUserIdAndJobIdOrderByCreatedAtDesc(any(), any());
        verify(retries, never()).countByApplicationExecutionId(any());
    }

    @Test
    void dependentJoinsAreSkippedEntirelyWhenTheirParentSetIsEmpty() {
        // Reviews, status history and retry counts hang off packages/lifecycles/executions, all of
        // which are dark by default. With no parents there is nothing to look up — and an IN () with
        // no values would be invalid SQL, so the guard is correctness, not just economy.
        svc.assembleAll(userId, List.of(app(), app()));

        verify(applicationReviews, never()).findReviewedPackageIds(any());
        verify(statusHistory, never()).findLatestChangePerLifecycle(any());
        verify(retries, never()).countPerExecution(any());
    }

    // ── Phase 7.16.3 — Automation Recovery Center visibility on the card ──

    @Test
    void automationFieldsAreNullWhenNoExecutionExists() {
        ApplicationCardResponse card = svc.assemble(userId, app());
        assertThat(card.executionStatus()).isNull();
        assertThat(card.automationHealth()).isNull();
        assertThat(card.retryCount()).isNull();
        assertThat(card.verificationStatus()).isNull();
    }

    @Test
    void automationFieldsReflectLatestExecution() {
        UUID execId = UUID.randomUUID();
        ApplicationExecution exec = ApplicationExecution.builder()
                .id(execId).userId(userId).jobId(jobId)
                .executionStatus(ApplicationExecution.STATUS_RETRY)
                .build();
        stubExecution(exec, 2L);

        ApplicationCardResponse card = svc.assemble(userId, app());

        assertThat(card.executionStatus()).isEqualTo(ApplicationExecution.STATUS_RETRY);
        assertThat(card.automationHealth()).isEqualTo("RETRYING");
        assertThat(card.retryCount()).isEqualTo(2);
    }

    @Test
    void submittedAndVerifiedIsCompleted() {
        UUID execId = UUID.randomUUID();
        ApplicationExecution exec = ApplicationExecution.builder()
                .id(execId).userId(userId).jobId(jobId)
                .executionStatus(ApplicationExecution.STATUS_SUBMITTED)
                .verificationStatus("VERIFIED")
                .build();
        stubExecution(exec, null);

        ApplicationCardResponse card = svc.assemble(userId, app());

        assertThat(card.automationHealth()).isEqualTo("COMPLETED");
    }

    @Test
    void submittedButNotVerifiedIsVerificationFailed() {
        UUID execId = UUID.randomUUID();
        ApplicationExecution exec = ApplicationExecution.builder()
                .id(execId).userId(userId).jobId(jobId)
                .executionStatus(ApplicationExecution.STATUS_SUBMITTED)
                .verificationStatus("UNABLE_TO_VERIFY")
                .build();
        stubExecution(exec, null);

        ApplicationCardResponse card = svc.assemble(userId, app());

        assertThat(card.automationHealth()).isEqualTo("VERIFICATION_FAILED");
    }

    @Test
    void submittedViaRetryChainIsRecovered() {
        UUID execId = UUID.randomUUID();
        ApplicationExecution exec = ApplicationExecution.builder()
                .id(execId).userId(userId).jobId(jobId)
                .executionStatus(ApplicationExecution.STATUS_SUBMITTED)
                .retryOfExecutionId(UUID.randomUUID())
                .build();
        stubExecution(exec, null);

        ApplicationCardResponse card = svc.assemble(userId, app());

        assertThat(card.automationHealth()).isEqualTo("RECOVERED");
    }

    // ── Guided Apply ──

    @Test
    void abortedByCaptchaIsGuidedApplyRequiredNotFailed() {
        ApplicationExecution exec = ApplicationExecution.builder()
                .id(UUID.randomUUID()).userId(userId).jobId(jobId)
                .executionStatus(ApplicationExecution.STATUS_ABORTED)
                .failureReason("captcha or login wall detected — routed to human review")
                .build();
        stubExecution(exec, null);

        ApplicationCardResponse card = svc.assemble(userId, app());

        assertThat(card.automationHealth()).isEqualTo("GUIDED_APPLY_REQUIRED");
        assertThat(card.guidedApplyRequired()).isTrue();
        assertThat(card.blockerReason()).isEqualTo("CAPTCHA");
        assertThat(card.blockerDetail()).contains("captcha");
    }

    /**
     * Guided Apply Hardening — regression for a real stale-state bug: {@code guidedApplyRequired}
     * was derived solely from {@code ApplicationExecution}, a completely separate entity from the
     * {@code ApplicationSubmissionSession} row the "Yes, I submitted it" confirmation writes to. A
     * user who explicitly confirmed manual submission would see the "Guided Apply Required" banner
     * forever, because nothing about the execution row ever changes in response to that action.
     */
    @Test
    void guidedApplyIsSuppressedOnceTheUserHasReportedManualSubmission() {
        ApplicationExecution exec = ApplicationExecution.builder()
                .id(UUID.randomUUID()).userId(userId).jobId(jobId)
                .executionStatus(ApplicationExecution.STATUS_ABORTED)
                .failureReason("captcha or login wall detected — routed to human review")
                .build();
        stubExecution(exec, null);
        ai.careerpilot.domain.ApplicationSubmissionSession session =
                ai.careerpilot.domain.ApplicationSubmissionSession.builder()
                        .id(UUID.randomUUID()).userId(userId).jobId(jobId)
                        .status(ai.careerpilot.domain.ApplicationSubmissionSession.STATUS_USER_REPORTED_SUBMITTED)
                        .submissionMethod(ai.careerpilot.domain.ApplicationSubmissionSession.METHOD_MANUAL)
                        .build();
        when(submissionSessions.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(session));

        ApplicationCardResponse card = svc.assemble(userId, app());

        // automationHealth is left alone (it still honestly reflects the execution row) but the
        // boolean that actually drives the frontend's banner/CTA must reflect the user's own action.
        assertThat(card.automationHealth()).isEqualTo("GUIDED_APPLY_REQUIRED");
        assertThat(card.guidedApplyRequired()).isFalse();
        assertThat(card.blockerReason()).isNull();
        assertThat(card.blockerDetail()).isNull();
    }

    /**
     * Final Hardening Pass — widened suppression: a LATER session for the same job that genuinely
     * reached SUBMIT_UNVERIFIED (automation actually clicked submit, just couldn't verify delivery)
     * must also clear the stale banner from an EARLIER aborted execution — re-showing "Guided Apply
     * Required" here would invite the user to apply a second time to a job that may already have
     * received a real submission.
     */
    @Test
    void guidedApplyIsSuppressedWhenALaterSessionReachedSubmitUnverified() {
        ApplicationExecution exec = ApplicationExecution.builder()
                .id(UUID.randomUUID()).userId(userId).jobId(jobId)
                .executionStatus(ApplicationExecution.STATUS_ABORTED)
                .failureReason("captcha or login wall detected — routed to human review")
                .build();
        stubExecution(exec, null);
        ai.careerpilot.domain.ApplicationSubmissionSession session =
                ai.careerpilot.domain.ApplicationSubmissionSession.builder()
                        .id(UUID.randomUUID()).userId(userId).jobId(jobId)
                        .status(ai.careerpilot.domain.ApplicationSubmissionSession.STATUS_SUBMIT_UNVERIFIED)
                        .submissionMethod(ai.careerpilot.domain.ApplicationSubmissionSession.METHOD_AUTO)
                        .build();
        when(submissionSessions.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(session));

        ApplicationCardResponse card = svc.assemble(userId, app());

        assertThat(card.guidedApplyRequired()).isFalse();
    }

    /** Any other submission-session status must NOT suppress a genuine, still-open Guided Apply blocker. */
    @Test
    void guidedApplyStaysRequiredWhenTheLatestSubmissionSessionIsStillWaiting() {
        ApplicationExecution exec = ApplicationExecution.builder()
                .id(UUID.randomUUID()).userId(userId).jobId(jobId)
                .executionStatus(ApplicationExecution.STATUS_ABORTED)
                .failureReason("captcha or login wall detected — routed to human review")
                .build();
        stubExecution(exec, null);
        ai.careerpilot.domain.ApplicationSubmissionSession session =
                ai.careerpilot.domain.ApplicationSubmissionSession.builder()
                        .id(UUID.randomUUID()).userId(userId).jobId(jobId)
                        .status(ai.careerpilot.domain.ApplicationSubmissionSession.STATUS_WAITING_MANUAL_SUBMISSION)
                        .submissionMethod(ai.careerpilot.domain.ApplicationSubmissionSession.METHOD_MANUAL)
                        .build();
        when(submissionSessions.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(session));

        ApplicationCardResponse card = svc.assemble(userId, app());

        assertThat(card.guidedApplyRequired()).isTrue();
        assertThat(card.blockerReason()).isEqualTo("CAPTCHA");
    }

    @Test
    void abortedWithUnspecificReasonIsManualRequiredNeverFabricated() {
        ApplicationExecution exec = ApplicationExecution.builder()
                .id(UUID.randomUUID()).userId(userId).jobId(jobId)
                .executionStatus(ApplicationExecution.STATUS_ABORTED)
                .failureReason("no execution backend configured (browser + ATS connectors disabled)")
                .build();
        stubExecution(exec, null);

        ApplicationCardResponse card = svc.assemble(userId, app());

        assertThat(card.guidedApplyRequired()).isTrue();
        assertThat(card.blockerReason()).isEqualTo("MANUAL_REQUIRED");
    }

    @Test
    void realTechnicalFailureStaysFailedNotGuidedApply() {
        // A thrown exception mid-attempt is a genuine failure, not a legitimate automation stop —
        // must never be relabelled as something the user is expected to complete manually.
        ApplicationExecution exec = ApplicationExecution.builder()
                .id(UUID.randomUUID()).userId(userId).jobId(jobId)
                .executionStatus(ApplicationExecution.STATUS_FAILED)
                .failureReason("java.lang.RuntimeException: boom")
                .build();
        stubExecution(exec, null);

        ApplicationCardResponse card = svc.assemble(userId, app());

        assertThat(card.automationHealth()).isEqualTo("FAILED");
        assertThat(card.guidedApplyRequired()).isFalse();
        assertThat(card.blockerReason()).isNull();
        assertThat(card.blockerDetail()).isNull();
    }

    @Test
    void nonAbortedExecutionNeverReportsAGuidedApplyBlocker() {
        ApplicationExecution exec = ApplicationExecution.builder()
                .id(UUID.randomUUID()).userId(userId).jobId(jobId)
                .executionStatus(ApplicationExecution.STATUS_SUBMITTED)
                .build();
        stubExecution(exec, null);

        ApplicationCardResponse card = svc.assemble(userId, app());

        assertThat(card.guidedApplyRequired()).isFalse();
        assertThat(card.blockerReason()).isNull();
    }

    @Test
    void employerJobIdIsJoinedFromJobExternalId() {
        when(jobs.findAllById(any())).thenReturn(List.of(Job.builder()
                .id(jobId).title("Backend Engineer").company("Acme").externalId("GH-847291").build()));

        ApplicationCardResponse card = svc.assemble(userId, app());

        assertThat(card.employerJobId()).isEqualTo("GH-847291");
    }
}
