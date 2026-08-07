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
                executions, retries);

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
}
