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

        when(jobRecommendations.findByUserIdAndJobId(any(), any())).thenReturn(Optional.empty());
        when(resumeTailorings.findFirstByUserIdAndJobIdOrderByTailoringVersionDesc(any(), any())).thenReturn(Optional.empty());
        when(atsAnalyses.findFirstByUserIdAndJobIdOrderByCreatedAtDesc(any(), any())).thenReturn(Optional.empty());
        when(coverLetters.findByUserIdAndJobId(any(), any())).thenReturn(Optional.empty());
        when(applicationPackages.findByUserIdAndJobId(any(), any())).thenReturn(Optional.empty());
        when(candidateProfiles.findByUserId(any())).thenReturn(Optional.empty());
        when(lifecycles.findByUserIdAndJobId(any(), any())).thenReturn(Optional.empty());
        when(executions.findFirstByUserIdAndJobIdOrderByCreatedAtDesc(any(), any())).thenReturn(Optional.empty());
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
        when(jobs.findById(jobId)).thenReturn(Optional.of(Job.builder()
                .id(jobId).title("Backend Engineer").company("Acme").location("Remote")
                .description("desc").build()));

        ApplicationCardResponse card = svc.assemble(userId, app());

        assertThat(card.jobTitle()).isEqualTo("Backend Engineer");
        assertThat(card.company()).isEqualTo("Acme");
        assertThat(card.location()).isEqualTo("Remote");
    }

    @Test
    void missingJobDoesNotThrow() {
        when(jobs.findById(jobId)).thenReturn(Optional.empty());

        ApplicationCardResponse card = svc.assemble(userId, app());

        assertThat(card.jobTitle()).isNull();
        assertThat(card.company()).isNull();
    }

    @Test
    void healthRecommendationAndNextActionAreAlwaysPopulated() {
        when(jobs.findById(jobId)).thenReturn(Optional.empty());

        ApplicationCardResponse card = svc.assemble(userId, app());

        assertThat(card.healthStatus()).isNotNull();
        assertThat(card.recommendationAction()).isNotNull();
        assertThat(card.suggestedNextAction()).isNotBlank();
    }

    @Test
    void assembleAllProducesOneCardPerApplication() {
        when(jobs.findById(any())).thenReturn(Optional.empty());
        List<ApplicationCardResponse> results = svc.assembleAll(userId, List.of(app(), app()));
        assertThat(results).hasSize(2);
    }

    // ── Phase 7.16.3 — Automation Recovery Center visibility on the card ──

    @Test
    void automationFieldsAreNullWhenNoExecutionExists() {
        when(jobs.findById(any())).thenReturn(Optional.empty());
        ApplicationCardResponse card = svc.assemble(userId, app());
        assertThat(card.executionStatus()).isNull();
        assertThat(card.automationHealth()).isNull();
        assertThat(card.retryCount()).isNull();
        assertThat(card.verificationStatus()).isNull();
    }

    @Test
    void automationFieldsReflectLatestExecution() {
        when(jobs.findById(any())).thenReturn(Optional.empty());
        UUID execId = UUID.randomUUID();
        ApplicationExecution exec = ApplicationExecution.builder()
                .id(execId).userId(userId).jobId(jobId)
                .executionStatus(ApplicationExecution.STATUS_RETRY)
                .build();
        when(executions.findFirstByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId)).thenReturn(Optional.of(exec));
        when(retries.countByApplicationExecutionId(execId)).thenReturn(2L);

        ApplicationCardResponse card = svc.assemble(userId, app());

        assertThat(card.executionStatus()).isEqualTo(ApplicationExecution.STATUS_RETRY);
        assertThat(card.automationHealth()).isEqualTo("RETRYING");
        assertThat(card.retryCount()).isEqualTo(2);
    }

    @Test
    void submittedAndVerifiedIsCompleted() {
        when(jobs.findById(any())).thenReturn(Optional.empty());
        UUID execId = UUID.randomUUID();
        ApplicationExecution exec = ApplicationExecution.builder()
                .id(execId).userId(userId).jobId(jobId)
                .executionStatus(ApplicationExecution.STATUS_SUBMITTED)
                .verificationStatus("VERIFIED")
                .build();
        when(executions.findFirstByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId)).thenReturn(Optional.of(exec));

        ApplicationCardResponse card = svc.assemble(userId, app());

        assertThat(card.automationHealth()).isEqualTo("COMPLETED");
    }

    @Test
    void submittedButNotVerifiedIsVerificationFailed() {
        when(jobs.findById(any())).thenReturn(Optional.empty());
        UUID execId = UUID.randomUUID();
        ApplicationExecution exec = ApplicationExecution.builder()
                .id(execId).userId(userId).jobId(jobId)
                .executionStatus(ApplicationExecution.STATUS_SUBMITTED)
                .verificationStatus("UNABLE_TO_VERIFY")
                .build();
        when(executions.findFirstByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId)).thenReturn(Optional.of(exec));

        ApplicationCardResponse card = svc.assemble(userId, app());

        assertThat(card.automationHealth()).isEqualTo("VERIFICATION_FAILED");
    }

    @Test
    void submittedViaRetryChainIsRecovered() {
        when(jobs.findById(any())).thenReturn(Optional.empty());
        UUID execId = UUID.randomUUID();
        ApplicationExecution exec = ApplicationExecution.builder()
                .id(execId).userId(userId).jobId(jobId)
                .executionStatus(ApplicationExecution.STATUS_SUBMITTED)
                .retryOfExecutionId(UUID.randomUUID())
                .build();
        when(executions.findFirstByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId)).thenReturn(Optional.of(exec));

        ApplicationCardResponse card = svc.assemble(userId, app());

        assertThat(card.automationHealth()).isEqualTo("RECOVERED");
    }
}
