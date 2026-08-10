package ai.careerpilot.submission;

import ai.careerpilot.autopilot.provider.ApplicationProviderRegistry;
import ai.careerpilot.companyintel.CompanyKnowledgeService;
import ai.careerpilot.domain.ApplicationSubmissionSession;
import ai.careerpilot.execution.approval.ApprovalService;
import ai.careerpilot.execution.execution.ApplicationExecutionService;
import ai.careerpilot.execution.safety.SafetyEngine;
import ai.careerpilot.learning.LearningPipeline;
import ai.careerpilot.repo.ApplicationRepository;
import ai.careerpilot.repo.ApplicationSubmissionAnswerRepository;
import ai.careerpilot.repo.ApplicationSubmissionSessionRepository;
import ai.careerpilot.repo.JobRepository;
import ai.careerpilot.repo.StarStoryRepository;
import ai.careerpilot.repo.UserRepository;
import ai.careerpilot.resumetailoring.apppackage.ApplicationPackageService;
import ai.careerpilot.resumetailoring.coverletter.CoverLetterService;
import ai.careerpilot.resumetailoring.service.ResumeTailoringService;
import ai.careerpilot.review.ApplicationReviewPipeline;
import ai.careerpilot.service.ApplicationService;
import ai.careerpilot.story.recommender.StoryRecommendationEngine;
import ai.careerpilot.submission.answer.AnswerGenerationService;
import ai.careerpilot.submission.mapping.FieldMappingService;
import ai.careerpilot.submission.question.QuestionDetectionService;
import ai.careerpilot.submission.reuse.ApplicationReuseResolver;
import ai.careerpilot.submission.validation.JobValidationService;
import ai.careerpilot.workflow.tracking.ApplicationLifecycleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Production incident (Phase F): submission pipelines hung forever at an intermediate stage.
 *
 * <p>Two real sessions were found stranded — one at {@code VALIDATING} (1s after creation) and one
 * at {@code STAR_READY} (after 148s and 10 of 11 generated answers). Neither ever reached
 * {@code FAILED}. Backend logs showed the owning executor thread's last line mid-AI-call, then a
 * fresh JVM banner with <b>no graceful-shutdown record at all</b>: the process was hard-killed and
 * the single thread that owns every status transition for a session simply ceased to exist. Nothing
 * else in the system writes that column, so the row had no remaining writer and no outgoing edge.
 *
 * <p>These tests pin that no session can remain in an intermediate state indefinitely, and — just as
 * importantly — that recovery never fabricates a verdict for a session that may already have
 * reached an employer.
 */
class SubmissionSessionReaperTest {

    private ApplicationSubmissionSessionRepository sessions;
    private List<ApplicationSubmissionSession> saved;

    @BeforeEach
    void setUp() {
        sessions = mock(ApplicationSubmissionSessionRepository.class);
        saved = new ArrayList<>();
        when(sessions.save(any(ApplicationSubmissionSession.class))).thenAnswer(inv -> {
            saved.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(sessions.findByStatusInAndUpdatedAtBefore(anyList(), any())).thenReturn(List.of());
    }

    private ApplicationSubmissionSessionService service(boolean enabled) {
        return new ApplicationSubmissionSessionService(
                sessions, mock(ApplicationSubmissionAnswerRepository.class), mock(JobRepository.class),
                mock(StarStoryRepository.class), mock(ApplicationRepository.class), mock(UserRepository.class),
                mock(JobValidationService.class), mock(ResumeTailoringService.class),
                mock(CoverLetterService.class), mock(ApplicationPackageService.class),
                mock(ApplicationReviewPipeline.class), mock(CompanyKnowledgeService.class),
                mock(StoryRecommendationEngine.class), mock(FieldMappingService.class),
                mock(QuestionDetectionService.class), mock(AnswerGenerationService.class),
                mock(ApplicationReuseResolver.class),
                mock(ApplicationProviderRegistry.class), mock(SafetyEngine.class),
                mock(ApprovalService.class), mock(ApplicationExecutionService.class),
                mock(ApplicationLifecycleService.class), mock(ApplicationService.class),
                mock(LearningPipeline.class), mock(ApplicationEventPublisher.class),
                mock(ThreadPoolTaskExecutor.class), enabled, false, false, false);
    }

    private ApplicationSubmissionSession stale(String status) {
        return ApplicationSubmissionSession.builder()
                .id(UUID.randomUUID()).userId(UUID.randomUUID()).jobId(UUID.randomUUID())
                .status(status)
                .submissionMethod(ApplicationSubmissionSession.METHOD_MANUAL)
                .updatedAt(Instant.now().minus(Duration.ofHours(11)))
                .build();
    }

    private void strandedPreSubmit(ApplicationSubmissionSession s) {
        when(sessions.findByStatusInAndUpdatedAtBefore(
                eq(ApplicationSubmissionSessionService.REAPABLE_PRE_SUBMIT_STATUSES), any()))
                .thenReturn(List.of(s));
    }

    // ── No intermediate state can persist indefinitely ──

    @ParameterizedTest(name = "a session stranded at {0} is failed, never left hanging")
    @ValueSource(strings = {"CREATED", "VALIDATING", "PACKAGE_READY", "REVIEW_READY",
            "COMPANY_READY", "STAR_READY", "READY_FOR_SUBMISSION"})
    void everyPreSubmitIntermediateStateIsRecoverable(String status) {
        ApplicationSubmissionSession s = stale(status);
        when(sessions.findByStatusInAndUpdatedAtBefore(anyList(), any()))
                .thenAnswer(inv -> {
                    List<String> asked = inv.getArgument(0);
                    return asked.contains(status) ? List.of(s) : List.of();
                });

        int failed = service(true).reapStranded(Duration.ofMinutes(30));

        assertThat(failed).isEqualTo(1);
        assertThat(s.getStatus()).isEqualTo(ApplicationSubmissionSession.STATUS_FAILED);
        assertThat(s.getCompletedAt()).isNotNull();
        assertThat(saved).contains(s);
    }

    @Test
    @DisplayName("the exact incident row — VALIDATING for 11 hours — reaches a terminal state")
    void reproducesTheIncidentSessionStuckAtValidating() {
        ApplicationSubmissionSession s = stale(ApplicationSubmissionSession.STATUS_VALIDATING);
        strandedPreSubmit(s);

        service(true).reapStranded(Duration.ofMinutes(30));

        assertThat(SubmissionStateMachine.isTerminal(s.getStatus())).isTrue();
        assertThat(s.getFailureReason()).contains("stranded");
        // The user must be able to tell that nothing reached an employer, and what to do next.
        assertThat(s.getFailureReason()).contains("nothing was submitted to any employer");
        assertThat(s.getFailureReason()).contains("Re-apply");
    }

    // ── Recovery must never fabricate a verdict ──

    @ParameterizedTest(name = "a session stranded at {0} is NOT auto-failed")
    @ValueSource(strings = {"SUBMITTING", "SUBMITTED", "VERIFYING", "VERIFIED",
            "VERIFICATION_FAILED", "SUBMIT_UNVERIFIED", "TRACKING"})
    void postSubmitStrandingsAreNeverRelabelledFailed(String status) {
        // Once a submit attempt has begun the application may genuinely be with the employer.
        // Marking it FAILED would tell the user their application failed when it may have succeeded.
        assertThat(ApplicationSubmissionSessionService.REAPABLE_PRE_SUBMIT_STATUSES)
                .doesNotContain(status);

        ApplicationSubmissionSession s = stale(status);
        when(sessions.findByStatusInAndUpdatedAtBefore(anyList(), any()))
                .thenAnswer(inv -> {
                    List<String> asked = inv.getArgument(0);
                    return asked.contains(status) ? List.of(s) : List.of();
                });

        int failed = service(true).reapStranded(Duration.ofMinutes(30));

        assertThat(failed).isZero();
        assertThat(s.getStatus()).isEqualTo(status);
        assertThat(saved).isEmpty();
    }

    @Test
    @DisplayName("a session parked at WAITING_APPROVAL is never reaped — a human may take days")
    void waitingApprovalIsNotReapable() {
        assertThat(ApplicationSubmissionSessionService.REAPABLE_PRE_SUBMIT_STATUSES)
                .doesNotContain(ApplicationSubmissionSession.STATUS_WAITING_APPROVAL,
                        ApplicationSubmissionSession.STATUS_WAITING_MANUAL_SUBMISSION);
    }

    @Test
    @DisplayName("terminal sessions are never touched")
    void terminalStatusesAreNotReapable() {
        assertThat(ApplicationSubmissionSessionService.REAPABLE_PRE_SUBMIT_STATUSES)
                .noneMatch(SubmissionStateMachine::isTerminal);
    }

    // ── Gating ──

    @Test
    void disabledSubmissionPipelineMeansNoQueriesAtAll() {
        assertThat(service(false).reapStranded(Duration.ofMinutes(30))).isZero();
        verifyNoInteractions(sessions);
    }

    @Test
    void oneFailingRowDoesNotStopTheRest() {
        ApplicationSubmissionSession bad = stale(ApplicationSubmissionSession.STATUS_VALIDATING);
        ApplicationSubmissionSession good = stale(ApplicationSubmissionSession.STATUS_STAR_READY);
        when(sessions.findByStatusInAndUpdatedAtBefore(
                eq(ApplicationSubmissionSessionService.REAPABLE_PRE_SUBMIT_STATUSES), any()))
                .thenReturn(List.of(bad, good));
        when(sessions.save(bad)).thenThrow(new RuntimeException("db blip"));

        int failed = service(true).reapStranded(Duration.ofMinutes(30));

        assertThat(failed).isEqualTo(1);
        assertThat(good.getStatus()).isEqualTo(ApplicationSubmissionSession.STATUS_FAILED);
    }

    // ── The trigger ──

    @Test
    @DisplayName("startup sweep runs — the stranding it recovers happened before this JVM existed")
    void startupSweepIsWired() {
        ApplicationSubmissionSessionService svc = mock(ApplicationSubmissionSessionService.class);
        new SubmissionSessionReaper(svc, true, 30).reapOnStartup();
        verify(svc).reapStranded(Duration.ofMinutes(30));
    }

    @Test
    void periodicSweepRuns() {
        ApplicationSubmissionSessionService svc = mock(ApplicationSubmissionSessionService.class);
        new SubmissionSessionReaper(svc, true, 30).reapPeriodically();
        verify(svc).reapStranded(Duration.ofMinutes(30));
    }

    @Test
    void reaperDisabledDoesNothing() {
        ApplicationSubmissionSessionService svc = mock(ApplicationSubmissionSessionService.class);
        SubmissionSessionReaper reaper = new SubmissionSessionReaper(svc, false, 30);
        reaper.reapOnStartup();
        reaper.reapPeriodically();
        verify(svc, never()).reapStranded(any());
    }

    @Test
    @DisplayName("a throwing sweep never propagates out of the scheduler")
    void sweepFailureIsContained() {
        ApplicationSubmissionSessionService svc = mock(ApplicationSubmissionSessionService.class);
        when(svc.reapStranded(any())).thenThrow(new RuntimeException("boom"));
        SubmissionSessionReaper reaper = new SubmissionSessionReaper(svc, true, 30);
        reaper.reapOnStartup();
        reaper.reapPeriodically();
    }
}
