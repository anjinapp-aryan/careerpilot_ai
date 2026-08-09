package ai.careerpilot.submission;

import ai.careerpilot.autopilot.provider.ApplicationProviderRegistry;
import ai.careerpilot.companyintel.CompanyKnowledgeService;
import ai.careerpilot.domain.*;
import ai.careerpilot.execution.approval.ApprovalService;
import ai.careerpilot.execution.event.ApprovalGrantedEvent;
import ai.careerpilot.execution.execution.ApplicationExecutionService;
import ai.careerpilot.execution.safety.SafetyEngine;
import ai.careerpilot.execution.safety.SafetyResult;
import ai.careerpilot.execution.safety.SafetyVerdict;
import ai.careerpilot.learning.LearningEventType;
import ai.careerpilot.learning.LearningPipeline;
import ai.careerpilot.repo.ApplicationRepository;
import ai.careerpilot.repo.UserRepository;
import ai.careerpilot.repo.ApplicationSubmissionAnswerRepository;
import ai.careerpilot.repo.ApplicationSubmissionSessionRepository;
import ai.careerpilot.repo.JobRepository;
import ai.careerpilot.repo.StarStoryRepository;
import ai.careerpilot.resumetailoring.apppackage.ApplicationPackageService;
import ai.careerpilot.resumetailoring.coverletter.CoverLetterService;
import ai.careerpilot.resumetailoring.service.ResumeTailoringService;
import ai.careerpilot.review.ApplicationReviewPipeline;
import ai.careerpilot.service.ApplicationService;
import ai.careerpilot.story.recommender.StoryRecommendationEngine;
import ai.careerpilot.submission.answer.AnswerGenerationService;
import ai.careerpilot.submission.answer.AnswerGenerationService.GeneratedAnswer;
import ai.careerpilot.submission.mapping.FieldMappingService;
import ai.careerpilot.submission.question.QuestionCategory;
import ai.careerpilot.submission.question.QuestionDetectionService;
import ai.careerpilot.submission.validation.JobValidationService;
import ai.careerpilot.submission.validation.JobValidationService.ValidationResult;
import ai.careerpilot.workflow.tracking.ApplicationLifecycleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Phase 7.16 — the orchestrator drives {@code runPipeline} (steps 1-8) and {@code
 * continueAfterApproval} (steps 9-13) directly (bypassing the async executor) for deterministic
 * tests, mirroring how {@code ApplicationExecutionServiceTest} calls {@code execute(...)} directly.
 * Every collaborator is a Mockito mock; the session row is a single mutable in-memory object shared
 * across the `sessions` repo stubs (save/findById), matching how the real repo would look to the
 * service across one HTTP request.
 */
class ApplicationSubmissionSessionServiceTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();

    private ApplicationSubmissionSessionRepository sessions;
    private ApplicationSubmissionAnswerRepository answers;
    private JobRepository jobs;
    private StarStoryRepository starStories;
    private ApplicationRepository applications;
    private UserRepository users;
    private JobValidationService jobValidation;
    private ResumeTailoringService tailoringService;
    private CoverLetterService coverLetterService;
    private ApplicationPackageService packageService;
    private ApplicationReviewPipeline reviewPipeline;
    private CompanyKnowledgeService companyKnowledgeService;
    private StoryRecommendationEngine storyEngine;
    private FieldMappingService fieldMapping;
    private QuestionDetectionService questionDetection;
    private AnswerGenerationService answerGeneration;
    private ApplicationProviderRegistry providerRegistry;
    private SafetyEngine safetyEngine;
    private ApprovalService approvalService;
    private ApplicationExecutionService executionService;
    private ApplicationLifecycleService lifecycleService;
    private ApplicationService applicationService;
    private LearningPipeline learningPipeline;
    private ApplicationEventPublisher events;
    private ThreadPoolTaskExecutor executor;

    private ApplicationSubmissionSession session;
    private Job job;
    private ResumeTailoring tailoring;
    private ApplicationPackage pkg;

    @BeforeEach
    void setUp() {
        sessions = mock(ApplicationSubmissionSessionRepository.class);
        answers = mock(ApplicationSubmissionAnswerRepository.class);
        jobs = mock(JobRepository.class);
        starStories = mock(StarStoryRepository.class);
        applications = mock(ApplicationRepository.class);
        users = mock(UserRepository.class);
        jobValidation = mock(JobValidationService.class);
        tailoringService = mock(ResumeTailoringService.class);
        coverLetterService = mock(CoverLetterService.class);
        packageService = mock(ApplicationPackageService.class);
        reviewPipeline = mock(ApplicationReviewPipeline.class);
        companyKnowledgeService = mock(CompanyKnowledgeService.class);
        storyEngine = mock(StoryRecommendationEngine.class);
        fieldMapping = mock(FieldMappingService.class);
        questionDetection = mock(QuestionDetectionService.class);
        answerGeneration = mock(AnswerGenerationService.class);
        providerRegistry = mock(ApplicationProviderRegistry.class);
        safetyEngine = mock(SafetyEngine.class);
        approvalService = mock(ApprovalService.class);
        executionService = mock(ApplicationExecutionService.class);
        lifecycleService = mock(ApplicationLifecycleService.class);
        applicationService = mock(ApplicationService.class);
        learningPipeline = mock(LearningPipeline.class);
        events = mock(ApplicationEventPublisher.class);
        executor = mock(ThreadPoolTaskExecutor.class);

        session = ApplicationSubmissionSession.builder()
                .id(sessionId).userId(userId).jobId(jobId)
                .status(ApplicationSubmissionSession.STATUS_CREATED)
                .submissionMethod(ApplicationSubmissionSession.METHOD_MANUAL)
                .build();
        job = Job.builder().id(jobId).title("Engineer").company("Acme").description("d")
                .sourceUrl("https://boards.greenhouse.io/acme/jobs/1").build();
        tailoring = ResumeTailoring.builder().id(UUID.randomUUID()).userId(userId).jobId(jobId)
                .originalResumeId(UUID.randomUUID()).tailoringVersion(1)
                .tailoredResumeText("tailored resume text").status(ResumeTailoring.STATUS_GENERATED).build();
        pkg = ApplicationPackage.builder().id(UUID.randomUUID()).userId(userId).jobId(jobId)
                .packageVersion(1).status(ApplicationPackage.STATUS_ASSEMBLED).metadata("{}").build();

        when(sessions.save(any(ApplicationSubmissionSession.class))).thenAnswer(inv -> inv.getArgument(0));
        when(sessions.findById(sessionId)).thenAnswer(inv -> Optional.of(session));

        when(jobValidation.validate(jobId)).thenReturn(ValidationResult.ok(job));
        when(tailoringService.latest(userId, jobId)).thenReturn(Optional.of(tailoring));
        when(coverLetterService.latest(userId, jobId)).thenReturn(Optional.of(
                CoverLetter.builder().id(UUID.randomUUID()).userId(userId).jobId(jobId).version(1).build()));
        when(packageService.latest(userId, jobId)).thenReturn(Optional.of(pkg));
        when(reviewPipeline.latest(pkg.getId())).thenReturn(Optional.of(
                ApplicationReview.builder().id(UUID.randomUUID()).applicationPackageId(pkg.getId())
                        .userId(userId).jobId(jobId).reviewVersion(1).build()));
        when(companyKnowledgeService.isEnabled()).thenReturn(false);
        when(storyEngine.isEnabled()).thenReturn(false);
        when(questionDetection.commonQuestions()).thenReturn(List.of());
        when(providerRegistry.providerNameFor(anyString())).thenReturn("greenhouse");
        when(safetyEngine.evaluate(eq(userId), eq(jobId), eq(pkg.getId())))
                .thenReturn(new SafetyResult(SafetyVerdict.SAFE, List.of()));
        when(applications.findFirstByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId)).thenReturn(Optional.empty());
    }

    private ApplicationSubmissionSessionService service(boolean enabled, boolean auto, boolean manual, boolean approval) {
        return new ApplicationSubmissionSessionService(sessions, answers, jobs, starStories, applications, users,
                jobValidation, tailoringService, coverLetterService, packageService, reviewPipeline,
                companyKnowledgeService, storyEngine, fieldMapping, questionDetection, answerGeneration,
                providerRegistry, safetyEngine, approvalService, executionService, lifecycleService,
                applicationService, learningPipeline, events, executor, enabled, auto, manual, approval);
    }

    private void stubExecutionSubmitted() {
        ApplicationExecution exec = ApplicationExecution.builder().id(UUID.randomUUID())
                .userId(userId).jobId(jobId).applicationPackageId(pkg.getId())
                .executionStatus(ApplicationExecution.STATUS_SUBMITTED)
                .executionType(ApplicationExecution.TYPE_MANUAL).attemptCount(1).build();
        when(executionService.execute(userId, jobId, pkg.getId())).thenReturn(Optional.of(exec));
    }

    /** Phase 7.16.1 — same as {@link #stubExecutionSubmitted()} but with real verification evidence already on the execution row (as SubmissionVerificationService would have set it). */
    private void stubExecutionSubmittedAndVerified() {
        ApplicationExecution exec = ApplicationExecution.builder().id(UUID.randomUUID())
                .userId(userId).jobId(jobId).applicationPackageId(pkg.getId())
                .executionStatus(ApplicationExecution.STATUS_SUBMITTED)
                .executionType(ApplicationExecution.TYPE_MANUAL).attemptCount(1)
                .confirmationNumber("real-evidence-blob").verificationStatus("VERIFIED").build();
        when(executionService.execute(userId, jobId, pkg.getId())).thenReturn(Optional.of(exec));
    }

    /** Captures every status the session passed through (by string, not by mutated-object reference — {@code session} is reused across advance() calls, so only string capture at save()-time is trustworthy). */
    private List<String> captureSessionStatusesOverTime() {
        List<String> statuses = new java.util.ArrayList<>();
        when(sessions.save(any(ApplicationSubmissionSession.class))).thenAnswer(inv -> {
            ApplicationSubmissionSession s = inv.getArgument(0);
            statuses.add(s.getStatus());
            return s;
        });
        return statuses;
    }

    // ── flags ──

    @Test
    void flagAccessorsReflectConstructorValues() {
        ApplicationSubmissionSessionService s = service(true, true, true, true);
        assertTrue(s.isEnabled());
        assertTrue(s.isAutoEnabled());
        assertTrue(s.isManualEnabled());
        assertTrue(s.isApprovalEnabled());

        ApplicationSubmissionSessionService disabled = service(false, false, false, false);
        assertFalse(disabled.isEnabled());
        assertFalse(disabled.isAutoEnabled());
        assertFalse(disabled.isManualEnabled());
        assertFalse(disabled.isApprovalEnabled());
    }

    // ── start() ──

    @Test
    void startReturnsEmptyAndCreatesNothingWhenDisabled() {
        ApplicationSubmissionSessionService s = service(false, false, false, false);
        assertTrue(s.start(userId, jobId, null).isEmpty());
        verifyNoInteractions(sessions, executor);
    }

    @Test
    void startCreatesSessionAndDispatchesToExecutorWhenEnabled() {
        ApplicationSubmissionSessionService s = service(true, false, true, false);
        Optional<ApplicationSubmissionSession> result = s.start(userId, jobId, null);
        assertTrue(result.isPresent());
        assertEquals(ApplicationSubmissionSession.STATUS_CREATED, result.get().getStatus());
        assertEquals(ApplicationSubmissionSession.METHOD_MANUAL, result.get().getSubmissionMethod());
        verify(sessions).save(any(ApplicationSubmissionSession.class));
        verify(executor).execute(any(Runnable.class));
    }

    @Test
    void startSurvivesExecutorRejection() {
        ApplicationSubmissionSessionService s = service(true, false, true, false);
        doThrow(new RuntimeException("queue full")).when(executor).execute(any(Runnable.class));
        assertDoesNotThrow(() -> s.start(userId, jobId, null));
    }

    @Test
    void startReusesRecentInFlightSessionInsteadOfCreatingDuplicate() {
        // A recent, still-running session for the same job must suppress a second pipeline (double-click).
        ApplicationSubmissionSession recent = ApplicationSubmissionSession.builder()
                .id(UUID.randomUUID()).userId(userId).jobId(jobId)
                .status(ApplicationSubmissionSession.STATUS_VALIDATING)
                .createdAt(Instant.now().minusSeconds(5)).build();
        when(sessions.findByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId)).thenReturn(List.of(recent));
        ApplicationSubmissionSessionService s = service(true, false, true, false);
        Optional<ApplicationSubmissionSession> result = s.start(userId, jobId, null);
        assertTrue(result.isPresent());
        assertEquals(recent.getId(), result.get().getId());
        verify(sessions, never()).save(any(ApplicationSubmissionSession.class));
        verify(executor, never()).execute(any(Runnable.class));
    }

    @Test
    void startAlwaysReusesWaitingApprovalSessionEvenIfOld() {
        // A session parked for human approval may sit for days — never spawn a duplicate for it.
        ApplicationSubmissionSession parked = ApplicationSubmissionSession.builder()
                .id(UUID.randomUUID()).userId(userId).jobId(jobId)
                .status(ApplicationSubmissionSession.STATUS_WAITING_APPROVAL)
                .createdAt(Instant.now().minus(Duration.ofDays(3))).build();
        when(sessions.findByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId)).thenReturn(List.of(parked));
        ApplicationSubmissionSessionService s = service(true, false, true, false);
        Optional<ApplicationSubmissionSession> result = s.start(userId, jobId, null);
        assertTrue(result.isPresent());
        assertEquals(parked.getId(), result.get().getId());
        verify(sessions, never()).save(any(ApplicationSubmissionSession.class));
    }

    @Test
    void startSupersedesStaleAbandonedSessionWithAFreshOne() {
        // A non-terminal session stranded long ago (backend restart / pre-fix race) must NOT block the
        // job forever — a fresh Apply supersedes it once it's past the staleness window.
        ApplicationSubmissionSession zombie = ApplicationSubmissionSession.builder()
                .id(UUID.randomUUID()).userId(userId).jobId(jobId)
                .status(ApplicationSubmissionSession.STATUS_CREATED)
                .createdAt(Instant.now().minus(Duration.ofHours(2))).build();
        when(sessions.findByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId)).thenReturn(List.of(zombie));
        ApplicationSubmissionSessionService s = service(true, false, true, false);
        Optional<ApplicationSubmissionSession> result = s.start(userId, jobId, null);
        assertTrue(result.isPresent());
        assertNotEquals(zombie.getId(), result.get().getId());
        verify(sessions).save(any(ApplicationSubmissionSession.class));
        verify(executor).execute(any(Runnable.class));
    }

    // ── find / history / queue / answersFor ──

    @Test
    void findDelegatesToRepo() {
        when(sessions.findByIdAndUserId(sessionId, userId)).thenReturn(Optional.of(session));
        ApplicationSubmissionSessionService s = service(true, false, true, false);
        assertTrue(s.find(sessionId, userId).isPresent());
        verify(sessions).findByIdAndUserId(sessionId, userId);
    }

    @Test
    void historyDelegatesToRepo() {
        ApplicationSubmissionSessionService s = service(true, false, true, false);
        s.history(userId, jobId);
        verify(sessions).findByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId);
    }

    @Test
    void queueDelegatesToRepoWithWaitingAndSubmittingStatuses() {
        ApplicationSubmissionSessionService s = service(true, false, true, false);
        s.queue();
        verify(sessions).findByStatusInOrderByCreatedAtDesc(
                List.of(ApplicationSubmissionSession.STATUS_WAITING_APPROVAL, ApplicationSubmissionSession.STATUS_SUBMITTING));
    }

    @Test
    void answersForDelegatesToRepo() {
        ApplicationSubmissionSessionService s = service(true, false, true, false);
        s.answersFor(sessionId);
        verify(answers).findBySessionIdOrderByCreatedAtAsc(sessionId);
    }

    // ── runPipeline: session missing ──

    @Test
    void runPipelineNoOpWhenSessionMissing() {
        when(sessions.findById(sessionId)).thenReturn(Optional.empty());
        ApplicationSubmissionSessionService s = service(true, false, true, false);
        assertDoesNotThrow(() -> s.runPipeline(sessionId));
        verify(jobValidation, never()).validate(any());
    }

    // ── runPipeline: happy path, no approval gate ──

    @Test
    void runPipelineHappyPathReachesCompletedWhenApprovalDisabled() {
        stubExecutionSubmitted();
        ApplicationSubmissionSessionService s = service(true, false, true, false);
        s.runPipeline(sessionId);

        assertEquals(ApplicationSubmissionSession.STATUS_COMPLETED, session.getStatus());
        assertEquals(pkg.getId(), session.getApplicationPackageId());
        assertEquals(tailoring.getId(), session.getResumeTailoringId());
        assertEquals("greenhouse", session.getProvider());
        assertNotNull(session.getCompletedAt());
        verify(learningPipeline).capture(eq(LearningEventType.APPLICATION_SUBMITTED), any(), eq(userId), eq(jobId), anyString(), anyString());
        verify(lifecycleService).transition(userId, jobId, ApplicationLifecycle.STATUS_SUBMITTED, "application-submission pipeline");
        verify(approvalService, never()).enqueue(any(), any(), any(), any());
    }

    // ── Phase 7.16.1 — VERIFIED is gated behind real evidence, VERIFICATION_FAILED is not a dead end ──

    @Test
    void runPipelineReachesVerificationFailedNotFabricatedVerifiedWhenNoEvidence() {
        // The common case today: stubExecutionSubmitted()'s execution row carries no
        // confirmationNumber/verificationStatus (exactly like real traffic before Gap D's guest-
        // apply path runs) — VERIFIED must never be fabricated for this.
        stubExecutionSubmitted();
        List<String> statuses = captureSessionStatusesOverTime();

        service(true, false, true, false).runPipeline(sessionId);

        assertTrue(statuses.contains(ApplicationSubmissionSession.STATUS_VERIFYING), statuses.toString());
        assertTrue(statuses.contains(ApplicationSubmissionSession.STATUS_VERIFICATION_FAILED), statuses.toString());
        assertFalse(statuses.contains(ApplicationSubmissionSession.STATUS_VERIFIED),
                "must never fabricate VERIFIED without real evidence: " + statuses);
        // Deliberately NOT a dead end — proceeds all the way to COMPLETED regardless.
        assertEquals(ApplicationSubmissionSession.STATUS_COMPLETED, session.getStatus());
    }

    @Test
    void runPipelineReachesVerifiedWhenExecutionHasRealEvidence() {
        stubExecutionSubmittedAndVerified();
        List<String> statuses = captureSessionStatusesOverTime();

        service(true, false, true, false).runPipeline(sessionId);

        assertTrue(statuses.contains(ApplicationSubmissionSession.STATUS_VERIFIED), statuses.toString());
        assertFalse(statuses.contains(ApplicationSubmissionSession.STATUS_VERIFICATION_FAILED), statuses.toString());
        assertEquals(ApplicationSubmissionSession.STATUS_COMPLETED, session.getStatus());
    }

    @Test
    void runPipelineExistingApplicationRowUpdatesItsStatus() {
        stubExecutionSubmitted();
        Application app = Application.builder().id(UUID.randomUUID()).userId(userId).orgId(UUID.randomUUID())
                .jobId(jobId).status("SAVED").build();
        when(applications.findFirstByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId)).thenReturn(Optional.of(app));

        ApplicationSubmissionSessionService s = service(true, false, true, false);
        s.runPipeline(sessionId);

        assertEquals(app.getId(), session.getApplicationId());
        verify(applicationService).updateStatus(userId, app.getId(), "APPLIED", "submitted via application-submission pipeline");
    }

    @Test
    void runPipelineCreatesAppliedKanbanCardWhenNoneExists() {
        // A native pipeline Apply on a discovered job has no prior kanban row — the completed
        // submission must CREATE one in APPLIED so it lands in the Applications board.
        stubExecutionSubmitted();
        UUID orgId = UUID.randomUUID();
        when(applications.findFirstByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId)).thenReturn(Optional.empty());
        when(users.findById(userId)).thenReturn(Optional.of(User.builder().id(userId).orgId(orgId).build()));
        Application created = Application.builder().id(UUID.randomUUID()).userId(userId).orgId(orgId)
                .jobId(jobId).status("APPLIED").build();
        when(applicationService.create(eq(userId), eq(orgId), any(Application.class))).thenReturn(created);

        ApplicationSubmissionSessionService s = service(true, false, true, false);
        s.runPipeline(sessionId);

        assertEquals(ApplicationSubmissionSession.STATUS_COMPLETED, session.getStatus());
        assertEquals(created.getId(), session.getApplicationId());
        verify(applicationService).create(eq(userId), eq(orgId), any(Application.class));
        verify(applicationService, never()).updateStatus(any(), any(), any(), any());
    }

    @Test
    void runPipelineStillCompletesWhenUserHasNoOrgForKanbanCard() {
        // Defensive: no orgId resolvable → skip card creation, log, but never fail the submission.
        stubExecutionSubmitted();
        when(applications.findFirstByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId)).thenReturn(Optional.empty());
        when(users.findById(userId)).thenReturn(Optional.empty());

        ApplicationSubmissionSessionService s = service(true, false, true, false);
        s.runPipeline(sessionId);

        assertEquals(ApplicationSubmissionSession.STATUS_COMPLETED, session.getStatus());
        verify(applicationService, never()).create(any(), any(), any());
    }

    @Test
    void runPipelineSurvivesApplicationServiceThrowingAndStillCompletes() {
        stubExecutionSubmitted();
        Application app = Application.builder().id(UUID.randomUUID()).userId(userId).orgId(UUID.randomUUID())
                .jobId(jobId).status("SAVED").build();
        when(applications.findFirstByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId)).thenReturn(Optional.of(app));
        doThrow(new RuntimeException("boom")).when(applicationService).updateStatus(any(), any(), any(), any());

        ApplicationSubmissionSessionService s = service(true, false, true, false);
        s.runPipeline(sessionId);

        assertEquals(ApplicationSubmissionSession.STATUS_COMPLETED, session.getStatus());
    }

    @Test
    void runPipelineTreatsEmptyExecutionResultAsNonFailureButWaitingManualSubmission() {
        // Phase 7.16.5 — no execution row at all (execution engine disabled) is not a FAILED
        // pipeline, but it is also not a genuine submission: never claim COMPLETED/SUBMITTED here.
        when(executionService.execute(userId, jobId, pkg.getId())).thenReturn(Optional.empty());
        ApplicationSubmissionSessionService s = service(true, false, true, false);
        s.runPipeline(sessionId);
        assertEquals(ApplicationSubmissionSession.STATUS_WAITING_MANUAL_SUBMISSION, session.getStatus());
        assertNull(session.getApplicationExecutionId());
        verifyNoInteractions(lifecycleService, learningPipeline);
        verify(applicationService, never()).create(any(), any(), any());
        verify(applicationService, never()).updateStatus(any(), any(), any(), any());
    }

    @Test
    void runPipelineAbortedExecutionRoutesToWaitingManualSubmissionNotSubmitted() {
        // Phase 7.16.5 — the core truthfulness bug: an ABORTED execution outcome (no execution
        // backend configured, or a connector present but not guest-apply-eligible — the overwhelming
        // majority of real traffic, since only Greenhouse/Lever have any real automation) must never
        // be relabeled SUBMITTED/COMPLETED.
        ApplicationExecution aborted = ApplicationExecution.builder().id(UUID.randomUUID())
                .userId(userId).jobId(jobId).applicationPackageId(pkg.getId())
                .executionStatus(ApplicationExecution.STATUS_ABORTED).executionType(ApplicationExecution.TYPE_MANUAL)
                .attemptCount(1).failureReason("submission not enabled in this build").build();
        when(executionService.execute(userId, jobId, pkg.getId())).thenReturn(Optional.of(aborted));

        ApplicationSubmissionSessionService s = service(true, false, true, false);
        s.runPipeline(sessionId);

        assertEquals(ApplicationSubmissionSession.STATUS_WAITING_MANUAL_SUBMISSION, session.getStatus());
        assertEquals(aborted.getId(), session.getApplicationExecutionId());
        verifyNoInteractions(lifecycleService, learningPipeline);
        verify(applicationService, never()).create(any(), any(), any());
        verify(applicationService, never()).updateStatus(any(), any(), any(), any());
    }

    @Test
    void runPipelineAwaitingApprovalExecutionRoutesToWaitingManualSubmissionNotSubmitted() {
        // Gap D's guest-apply flow can return AWAITING_APPROVAL synchronously (the form is filled and
        // screenshotted, but the actual submit click hasn't happened yet, pending human approval of
        // that screenshot) — this must not be mistaken for a completed submission either.
        ApplicationExecution awaiting = ApplicationExecution.builder().id(UUID.randomUUID())
                .userId(userId).jobId(jobId).applicationPackageId(pkg.getId())
                .executionStatus(ApplicationExecution.STATUS_AWAITING_APPROVAL)
                .executionType(ApplicationExecution.TYPE_ATS_CONNECTOR).attemptCount(1).build();
        when(executionService.execute(userId, jobId, pkg.getId())).thenReturn(Optional.of(awaiting));

        ApplicationSubmissionSessionService s = service(true, false, true, false);
        s.runPipeline(sessionId);

        assertEquals(ApplicationSubmissionSession.STATUS_WAITING_MANUAL_SUBMISSION, session.getStatus());
        verifyNoInteractions(lifecycleService, learningPipeline);
    }

    @Test
    void runPipelineExecutionFailedSetsSessionFailed() {
        ApplicationExecution failedExec = ApplicationExecution.builder().id(UUID.randomUUID())
                .userId(userId).jobId(jobId).applicationPackageId(pkg.getId())
                .executionStatus(ApplicationExecution.STATUS_FAILED).executionType(ApplicationExecution.TYPE_MANUAL)
                .attemptCount(1).failureReason("no backend configured").build();
        when(executionService.execute(userId, jobId, pkg.getId())).thenReturn(Optional.of(failedExec));

        ApplicationSubmissionSessionService s = service(true, false, true, false);
        s.runPipeline(sessionId);

        assertEquals(ApplicationSubmissionSession.STATUS_FAILED, session.getStatus());
        assertTrue(session.getFailureReason().contains("execution failed"));
        verify(learningPipeline, never()).capture(any(), any(), any(), any(), any(), any());
    }

    // ── runPipeline: fail-closed branches ──

    @Test
    void runPipelineJobValidationFailureSetsFailedWithReason() {
        when(jobValidation.validate(jobId)).thenReturn(ValidationResult.fail(List.of("job not found")));
        ApplicationSubmissionSessionService s = service(true, false, true, false);
        s.runPipeline(sessionId);
        assertEquals(ApplicationSubmissionSession.STATUS_FAILED, session.getStatus());
        assertTrue(session.getFailureReason().contains("job validation failed"));
        assertTrue(session.getFailureReason().contains("job not found"));
        verifyNoInteractions(tailoringService);
    }

    @Test
    void runPipelineNoPackageAssembledSetsFailedWithReason() {
        when(packageService.latest(userId, jobId)).thenReturn(Optional.empty());
        when(packageService.assemble(userId, jobId)).thenReturn(Optional.empty());
        ApplicationSubmissionSessionService s = service(true, false, true, false);
        s.runPipeline(sessionId);
        assertEquals(ApplicationSubmissionSession.STATUS_FAILED, session.getStatus());
        assertTrue(session.getFailureReason().contains("unable to assemble"));
    }

    @Test
    void runPipelineUnexpectedExceptionSetsFailed() {
        when(tailoringService.latest(userId, jobId)).thenThrow(new RuntimeException("db down"));
        ApplicationSubmissionSessionService s = service(true, false, true, false);
        s.runPipeline(sessionId);
        assertEquals(ApplicationSubmissionSession.STATUS_FAILED, session.getStatus());
        assertTrue(session.getFailureReason().contains("pipeline error"));
    }

    @Test
    void runPipelineApprovalEnqueueFailureSetsFailed() {
        when(safetyEngine.evaluate(eq(userId), eq(jobId), eq(pkg.getId())))
                .thenReturn(new SafetyResult(SafetyVerdict.SAFE, List.of()));
        when(approvalService.enqueue(eq(userId), eq(jobId), eq(pkg.getId()), anyString())).thenReturn(Optional.empty());
        ApplicationSubmissionSessionService s = service(true, false, true, true);
        s.runPipeline(sessionId);
        assertEquals(ApplicationSubmissionSession.STATUS_FAILED, session.getStatus());
        assertTrue(session.getFailureReason().contains("approval enqueue failed"));
    }

    // ── runPipeline: approval gate ──

    @Test
    void runPipelineStopsAtWaitingApprovalWhenApprovalEnabled() {
        UUID approvalId = UUID.randomUUID();
        when(approvalService.enqueue(eq(userId), eq(jobId), eq(pkg.getId()), anyString()))
                .thenReturn(Optional.of(ApprovalQueueEntry.builder().id(approvalId).userId(userId).jobId(jobId)
                        .applicationPackageId(pkg.getId()).safetyVerdict("SAFE")
                        .status(ApprovalQueueEntry.STATUS_PENDING).build()));

        ApplicationSubmissionSessionService s = service(true, false, true, true);
        s.runPipeline(sessionId);

        assertEquals(ApplicationSubmissionSession.STATUS_WAITING_APPROVAL, session.getStatus());
        assertEquals(approvalId, session.getApprovalQueueEntryId());
        verify(executionService, never()).execute(any(), any(), any());
    }

    // ── company brief + STAR story (best effort, optional) ──

    @Test
    void runPipelinePopulatesCompanyKnowledgeIdWhenCompanyIntelEnabled() {
        when(companyKnowledgeService.isEnabled()).thenReturn(true);
        UUID knowledgeId = UUID.randomUUID();
        when(companyKnowledgeService.findByName(userId, "Acme")).thenReturn(Optional.of(
                CompanyKnowledge.builder().id(knowledgeId).userId(userId).companyName("Acme")
                        .normalizedName("acme").knowledgeVersion(1).knowledge("Acme is a great place").build()));
        stubExecutionSubmitted();

        ApplicationSubmissionSessionService s = service(true, false, true, false);
        s.runPipeline(sessionId);

        assertEquals(knowledgeId, session.getCompanyKnowledgeId());
    }

    @Test
    void runPipelineResolvesBestFitStarStoryWhenStoryEngineEnabled() {
        when(storyEngine.isEnabled()).thenReturn(true);
        UUID storyId = UUID.randomUUID();
        StoryRecommendation rec = StoryRecommendation.builder().id(UUID.randomUUID()).userId(userId)
                .starStoryId(storyId).matchScore(80).build();
        when(storyEngine.recommend(eq(userId), eq("Acme"), eq("Engineer"), isNull(), eq(1))).thenReturn(List.of(rec));
        when(starStories.findById(storyId)).thenReturn(Optional.of(
                StarStory.builder().id(storyId).userId(userId).title("Story").currentVersion(1).build()));
        stubExecutionSubmitted();

        ApplicationSubmissionSessionService s = service(true, false, true, false);
        assertDoesNotThrow(() -> s.runPipeline(sessionId));
        assertEquals(ApplicationSubmissionSession.STATUS_COMPLETED, session.getStatus());
    }

    @Test
    void runPipelineGeneratesAnswersForEachCommonQuestion() {
        when(questionDetection.commonQuestions()).thenReturn(List.of(
                new AbstractMap.SimpleEntry<>("Why are you interested in this role?", QuestionCategory.WHY_ROLE)));
        when(answerGeneration.generate(eq(userId), eq(jobId), anyString(), eq(QuestionCategory.WHY_ROLE), any()))
                .thenReturn(new GeneratedAnswer("Why are you interested in this role?", QuestionCategory.WHY_ROLE,
                        "Because...", "{}"));
        stubExecutionSubmitted();

        ApplicationSubmissionSessionService s = service(true, false, true, false);
        s.runPipeline(sessionId);

        verify(answers).save(argThat(a -> "WHY_ROLE".equals(a.getQuestionCategory()) && "Because...".equals(a.getAnswerText())));
    }

    // ── onApprovalGranted / continueAfterApproval ──

    @Test
    void onApprovalGrantedNoOpWhenDisabled() {
        ApplicationSubmissionSessionService s = service(false, false, true, true);
        s.onApprovalGranted(new ApprovalGrantedEvent(userId, jobId, pkg.getId(), UUID.randomUUID(), "boss@x.com"));
        verifyNoInteractions(sessions);
    }

    @Test
    void onApprovalGrantedNoOpWhenApprovalGateDisabled() {
        ApplicationSubmissionSessionService s = service(true, false, true, false);
        s.onApprovalGranted(new ApprovalGrantedEvent(userId, jobId, pkg.getId(), UUID.randomUUID(), "boss@x.com"));
        verifyNoInteractions(sessions);
    }

    @Test
    void onApprovalGrantedNoOpWhenNoMatchingSession() {
        UUID approvalId = UUID.randomUUID();
        when(sessions.findByApprovalQueueEntryId(approvalId)).thenReturn(Optional.empty());
        ApplicationSubmissionSessionService s = service(true, false, true, true);
        s.onApprovalGranted(new ApprovalGrantedEvent(userId, jobId, pkg.getId(), approvalId, "boss@x.com"));
        verify(executor, never()).execute(any(Runnable.class));
    }

    @Test
    void onApprovalGrantedDispatchesToExecutorWhenMatchingSessionExists() {
        UUID approvalId = UUID.randomUUID();
        session.setApprovalQueueEntryId(approvalId);
        session.setStatus(ApplicationSubmissionSession.STATUS_WAITING_APPROVAL);
        when(sessions.findByApprovalQueueEntryId(approvalId)).thenReturn(Optional.of(session));

        ApplicationSubmissionSessionService s = service(true, false, true, true);
        s.onApprovalGranted(new ApprovalGrantedEvent(userId, jobId, pkg.getId(), approvalId, "boss@x.com"));

        verify(executor).execute(any(Runnable.class));
    }

    @Test
    void continueAfterApprovalNoOpWhenSessionMissing() {
        when(sessions.findById(sessionId)).thenReturn(Optional.empty());
        ApplicationSubmissionSessionService s = service(true, false, true, true);
        assertDoesNotThrow(() -> s.continueAfterApproval(sessionId));
        verifyNoInteractions(jobs);
    }

    @Test
    void continueAfterApprovalNoOpWhenSessionNotWaitingApproval() {
        session.setStatus(ApplicationSubmissionSession.STATUS_CREATED);
        ApplicationSubmissionSessionService s = service(true, false, true, true);
        s.continueAfterApproval(sessionId);
        assertEquals(ApplicationSubmissionSession.STATUS_CREATED, session.getStatus());
        verifyNoInteractions(jobs);
    }

    @Test
    void continueAfterApprovalFailsWhenJobNoLongerExists() {
        session.setStatus(ApplicationSubmissionSession.STATUS_WAITING_APPROVAL);
        session.setApplicationPackageId(pkg.getId());
        when(jobs.findById(jobId)).thenReturn(Optional.empty());

        ApplicationSubmissionSessionService s = service(true, false, true, true);
        s.continueAfterApproval(sessionId);

        assertEquals(ApplicationSubmissionSession.STATUS_FAILED, session.getStatus());
        assertTrue(session.getFailureReason().contains("job no longer exists"));
    }

    @Test
    void continueAfterApprovalHappyPathReachesCompleted() {
        session.setStatus(ApplicationSubmissionSession.STATUS_WAITING_APPROVAL);
        session.setApplicationPackageId(pkg.getId());
        when(jobs.findById(jobId)).thenReturn(Optional.of(job));
        stubExecutionSubmitted();

        ApplicationSubmissionSessionService s = service(true, false, true, true);
        s.continueAfterApproval(sessionId);

        assertEquals(ApplicationSubmissionSession.STATUS_COMPLETED, session.getStatus());
        verify(learningPipeline).capture(eq(LearningEventType.APPLICATION_SUBMITTED), any(), eq(userId), eq(jobId), any(), anyString());
    }

    @Test
    void continueAfterApprovalExecutionFailureSetsFailed() {
        session.setStatus(ApplicationSubmissionSession.STATUS_WAITING_APPROVAL);
        session.setApplicationPackageId(pkg.getId());
        when(jobs.findById(jobId)).thenReturn(Optional.of(job));
        ApplicationExecution failedExec = ApplicationExecution.builder().id(UUID.randomUUID())
                .userId(userId).jobId(jobId).applicationPackageId(pkg.getId())
                .executionStatus(ApplicationExecution.STATUS_FAILED).executionType(ApplicationExecution.TYPE_MANUAL)
                .attemptCount(1).failureReason("boom").build();
        when(executionService.execute(userId, jobId, pkg.getId())).thenReturn(Optional.of(failedExec));

        ApplicationSubmissionSessionService s = service(true, false, true, true);
        s.continueAfterApproval(sessionId);

        assertEquals(ApplicationSubmissionSession.STATUS_FAILED, session.getStatus());
        assertTrue(session.getFailureReason().contains("execution failed"));
    }

    @Test
    void continueAfterApprovalUnexpectedExceptionSetsFailed() {
        session.setStatus(ApplicationSubmissionSession.STATUS_WAITING_APPROVAL);
        session.setApplicationPackageId(pkg.getId());
        when(jobs.findById(jobId)).thenReturn(Optional.of(job));
        when(executionService.execute(any(), any(), any())).thenThrow(new RuntimeException("kaboom"));

        ApplicationSubmissionSessionService s = service(true, false, true, true);
        s.continueAfterApproval(sessionId);

        assertEquals(ApplicationSubmissionSession.STATUS_FAILED, session.getStatus());
        assertTrue(session.getFailureReason().contains("resume error"));
    }

    // ── Guided Apply — reportUserSubmitted ──

    /**
     * {@code claimUserReportedSubmitted} is the real atomic-UPDATE repository method (proven against
     * real Postgres in {@code ApplicationSubmissionSessionRepositoryClaimTest}); here it's a mock, so
     * this stub simulates what that UPDATE does to the row — the same convention {@code
     * stubWinningClaim}/{@code stubLosingClaim} already established for {@code ApprovalServiceTest}.
     */
    private void stubWinningReportSubmittedClaim() {
        when(sessions.claimUserReportedSubmitted(eq(sessionId), eq(userId),
                eq(ApplicationSubmissionSession.STATUS_USER_REPORTED_SUBMITTED), any(), any()))
                .thenAnswer(inv -> {
                    session.setStatus(inv.getArgument(2));
                    session.setUserReportedSubmittedAt(inv.getArgument(3));
                    session.setUserSubmissionNote(inv.getArgument(4));
                    session.setCompletedAt(inv.getArgument(3));
                    return 1;
                });
    }

    @Test
    void reportUserSubmittedFromWaitingManualSubmissionSucceeds() {
        session.setStatus(ApplicationSubmissionSession.STATUS_WAITING_MANUAL_SUBMISSION);
        when(sessions.findByIdAndUserId(sessionId, userId)).thenReturn(Optional.of(session));
        stubWinningReportSubmittedClaim();

        ApplicationSubmissionSessionService s = service(true, false, true, true);
        Optional<ApplicationSubmissionSession> result = s.reportUserSubmitted(userId, sessionId, "submitted via careers page");

        assertTrue(result.isPresent());
        assertEquals(ApplicationSubmissionSession.STATUS_USER_REPORTED_SUBMITTED, session.getStatus());
        assertNotNull(session.getUserReportedSubmittedAt());
        assertEquals("submitted via careers page", session.getUserSubmissionNote());
        assertNotNull(session.getCompletedAt());
    }

    @Test
    void reportUserSubmittedWithNoNoteLeavesNoteNull() {
        session.setStatus(ApplicationSubmissionSession.STATUS_WAITING_MANUAL_SUBMISSION);
        when(sessions.findByIdAndUserId(sessionId, userId)).thenReturn(Optional.of(session));
        stubWinningReportSubmittedClaim();

        service(true, false, true, true).reportUserSubmitted(userId, sessionId, null);

        assertEquals(ApplicationSubmissionSession.STATUS_USER_REPORTED_SUBMITTED, session.getStatus());
        assertNull(session.getUserSubmissionNote());
    }

    @Test
    void reportUserSubmittedIsRejectedWhenTheClaimLoses() {
        // Simulates the race this fix closes: another caller already won the atomic UPDATE, so this
        // caller's claim affects 0 rows even though its own initial read saw WAITING_MANUAL_SUBMISSION.
        session.setStatus(ApplicationSubmissionSession.STATUS_WAITING_MANUAL_SUBMISSION);
        when(sessions.findByIdAndUserId(sessionId, userId)).thenReturn(Optional.of(session));
        when(sessions.claimUserReportedSubmitted(eq(sessionId), eq(userId), any(), any(), any())).thenReturn(0);

        ApplicationSubmissionSessionService s = service(true, false, true, true);
        assertThrows(IllegalStateException.class, () -> s.reportUserSubmitted(userId, sessionId, null));
        // The loser's attempt must never mutate the shared row.
        assertEquals(ApplicationSubmissionSession.STATUS_WAITING_MANUAL_SUBMISSION, session.getStatus());
    }

    @Test
    void reportUserSubmittedFromAnyOtherStatusIsRejected() {
        session.setStatus(ApplicationSubmissionSession.STATUS_CREATED);
        when(sessions.findByIdAndUserId(sessionId, userId)).thenReturn(Optional.of(session));

        ApplicationSubmissionSessionService s = service(true, false, true, true);
        assertThrows(IllegalStateException.class, () -> s.reportUserSubmitted(userId, sessionId, null));
        // Never silently relabelled — status is untouched by the rejected attempt.
        assertEquals(ApplicationSubmissionSession.STATUS_CREATED, session.getStatus());
    }

    @Test
    void reportUserSubmittedForUnownedOrMissingSessionReturnsEmpty() {
        when(sessions.findByIdAndUserId(sessionId, userId)).thenReturn(Optional.empty());

        ApplicationSubmissionSessionService s = service(true, false, true, true);
        assertTrue(s.reportUserSubmitted(userId, sessionId, null).isEmpty());
    }
}
