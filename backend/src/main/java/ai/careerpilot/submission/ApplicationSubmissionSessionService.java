package ai.careerpilot.submission;

import ai.careerpilot.autopilot.provider.ApplicationProviderRegistry;
import ai.careerpilot.companyintel.CompanyKnowledgeService;
import ai.careerpilot.domain.*;
import ai.careerpilot.execution.approval.ApprovalService;
import ai.careerpilot.execution.event.ApprovalGrantedEvent;
import ai.careerpilot.execution.execution.ApplicationExecutionService;
import ai.careerpilot.execution.safety.SafetyEngine;
import ai.careerpilot.execution.safety.SafetyResult;
import ai.careerpilot.learning.LearningEventType;
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
import ai.careerpilot.submission.answer.AnswerGenerationService.AnswerContext;
import ai.careerpilot.submission.answer.AnswerGenerationService.GeneratedAnswer;
import ai.careerpilot.submission.config.SubmissionExecutorsConfig;
import ai.careerpilot.submission.event.ApplicationSubmissionSessionEvent;
import ai.careerpilot.submission.mapping.FieldMappingService;
import ai.careerpilot.submission.question.QuestionCategory;
import ai.careerpilot.submission.question.QuestionDetectionService;
import ai.careerpilot.submission.reuse.AnswerReuseDecision;
import ai.careerpilot.submission.reuse.ApplicationReuseResolver;
import ai.careerpilot.submission.reuse.JobFingerprint;
import ai.careerpilot.submission.validation.JobValidationService;
import ai.careerpilot.workflow.tracking.ApplicationLifecycleService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 7.16 — the orchestrator for one user-triggered Apply click: {@code CREATED -&gt;
 * VALIDATING -&gt; PACKAGE_READY -&gt; REVIEW_READY -&gt; COMPANY_READY -&gt; STAR_READY -&gt;
 * READY_FOR_SUBMISSION -&gt; [WAITING_APPROVAL] -&gt; SUBMITTING -&gt; SUBMITTED -&gt; VERIFIED
 * -&gt; TRACKING -&gt; COMPLETED}, fail-closed at every step (see class-level plan doc). This is
 * a THIN orchestration layer: every real capability (package assembly, review, company brief,
 * STAR recommendation, safety, approval, execution, tracking, learning) is delegated to the
 * existing dormant services named in each step below — nothing is reimplemented.
 *
 * <p>Flag-gated dark by {@code application.submission.enabled} (default false); with the flag off
 * every entry point is a no-op, so wiring this into the Apply button is zero behavior change until
 * explicitly enabled. Runs on a dedicated bounded executor so the triggering Apply click never
 * blocks.
 */
@Service
public class ApplicationSubmissionSessionService {

    private static final Logger log = LoggerFactory.getLogger(ApplicationSubmissionSessionService.class);

    /** A non-terminal, non-approval session older than this is treated as abandoned (see {@link #isLiveInFlight}). */
    private static final Duration STALE_SESSION_WINDOW = Duration.ofMinutes(15);

    private final ApplicationSubmissionSessionRepository sessions;
    private final ApplicationSubmissionAnswerRepository answers;
    private final JobRepository jobs;
    private final StarStoryRepository starStories;
    private final ApplicationRepository applications;
    private final UserRepository users;

    private final JobValidationService jobValidation;
    private final ResumeTailoringService tailoringService;
    private final CoverLetterService coverLetterService;
    private final ApplicationPackageService packageService;
    private final ApplicationReviewPipeline reviewPipeline;
    private final CompanyKnowledgeService companyKnowledgeService;
    private final StoryRecommendationEngine storyEngine;
    private final FieldMappingService fieldMapping;
    private final QuestionDetectionService questionDetection;
    private final AnswerGenerationService answerGeneration;
    private final ApplicationReuseResolver reuseResolver;
    private final ApplicationProviderRegistry providerRegistry;
    private final SafetyEngine safetyEngine;
    private final ApprovalService approvalService;
    private final ApplicationExecutionService executionService;
    private final ApplicationLifecycleService lifecycleService;
    private final ApplicationService applicationService;
    private final LearningPipeline learningPipeline;

    private final ApplicationEventPublisher events;
    private final ThreadPoolTaskExecutor executor;

    private final boolean enabled;
    private final boolean autoEnabled;
    private final boolean manualEnabled;
    private final boolean approvalEnabled;

    public ApplicationSubmissionSessionService(
            ApplicationSubmissionSessionRepository sessions, ApplicationSubmissionAnswerRepository answers,
            JobRepository jobs, StarStoryRepository starStories, ApplicationRepository applications,
            UserRepository users,
            JobValidationService jobValidation, ResumeTailoringService tailoringService,
            CoverLetterService coverLetterService, ApplicationPackageService packageService,
            ApplicationReviewPipeline reviewPipeline, CompanyKnowledgeService companyKnowledgeService,
            StoryRecommendationEngine storyEngine, FieldMappingService fieldMapping,
            QuestionDetectionService questionDetection, AnswerGenerationService answerGeneration,
            ApplicationReuseResolver reuseResolver,
            ApplicationProviderRegistry providerRegistry, SafetyEngine safetyEngine,
            ApprovalService approvalService, ApplicationExecutionService executionService,
            ApplicationLifecycleService lifecycleService, ApplicationService applicationService,
            LearningPipeline learningPipeline, ApplicationEventPublisher events,
            @Qualifier(SubmissionExecutorsConfig.APPLICATION_SUBMISSION_EXECUTOR) ThreadPoolTaskExecutor executor,
            @Value("${application.submission.enabled:false}") boolean enabled,
            @Value("${application.submission.auto.enabled:false}") boolean autoEnabled,
            @Value("${application.submission.manual.enabled:false}") boolean manualEnabled,
            @Value("${application.submission.approval.enabled:false}") boolean approvalEnabled) {
        this.sessions = sessions;
        this.answers = answers;
        this.jobs = jobs;
        this.starStories = starStories;
        this.applications = applications;
        this.users = users;
        this.jobValidation = jobValidation;
        this.tailoringService = tailoringService;
        this.coverLetterService = coverLetterService;
        this.packageService = packageService;
        this.reviewPipeline = reviewPipeline;
        this.companyKnowledgeService = companyKnowledgeService;
        this.storyEngine = storyEngine;
        this.fieldMapping = fieldMapping;
        this.questionDetection = questionDetection;
        this.answerGeneration = answerGeneration;
        this.reuseResolver = reuseResolver;
        this.providerRegistry = providerRegistry;
        this.safetyEngine = safetyEngine;
        this.approvalService = approvalService;
        this.executionService = executionService;
        this.lifecycleService = lifecycleService;
        this.applicationService = applicationService;
        this.learningPipeline = learningPipeline;
        this.events = events;
        this.executor = executor;
        this.enabled = enabled;
        this.autoEnabled = autoEnabled;
        this.manualEnabled = manualEnabled;
        this.approvalEnabled = approvalEnabled;
    }

    public boolean isEnabled() { return enabled; }
    public boolean isAutoEnabled() { return autoEnabled; }
    public boolean isManualEnabled() { return manualEnabled; }
    public boolean isApprovalEnabled() { return approvalEnabled; }

    // ── Entry point (Apply button) ──

    /**
     * Start a new submission session for (userId, jobId) and dispatch the orchestration onto the
     * bounded executor — never blocks the caller. Empty when the master flag is off. Idempotent:
     * reuses an existing non-terminal session for the same (userId, jobId) instead of creating a
     * duplicate, so a double-click on Apply can't race two pipelines into the same package row.
     *
     * <p>Deliberately NOT {@code @Transactional}: {@link #sessions}.save(...) below must commit
     * before the async {@code runPipeline} dispatch reads it back on a different connection/thread
     * — wrapping this method in a transaction would leave the row uncommitted while the executor
     * thread's {@code findById} races ahead of it, silently returning empty and stranding the
     * session in {@code CREATED} forever (the exact class of bug documented on the resumetailoring
     * workers: never hand off to another thread from inside an open transaction).
     */
    public Optional<ApplicationSubmissionSession> start(UUID userId, UUID jobId, UUID resumeId) {
        if (!enabled) return Optional.empty();
        Optional<ApplicationSubmissionSession> inFlight = sessions.findByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId)
                .stream()
                .filter(this::isLiveInFlight)
                .findFirst();
        if (inFlight.isPresent()) {
            return inFlight;
        }
        ApplicationSubmissionSession session = sessions.save(ApplicationSubmissionSession.builder()
                .userId(userId).jobId(jobId).resumeId(resumeId)
                .status(ApplicationSubmissionSession.STATUS_CREATED)
                .submissionMethod(ApplicationSubmissionSession.METHOD_MANUAL)
                .startedAt(Instant.now())
                .build());
        publish(session);
        UUID sessionId = session.getId();
        try {
            executor.execute(() -> runPipeline(sessionId));
        } catch (Exception e) {
            log.warn("APP_SUBMISSION dispatch rejected session={}: {}", sessionId, e.toString());
        }
        return Optional.of(session);
    }

    /**
     * True when an existing session should suppress a fresh one for the same job. A session parked at
     * WAITING_APPROVAL is always live (a human may take days to decide, so never spawn a duplicate).
     * Any other non-terminal state counts as live only while recent — this both dedupes the Apply
     * double-click window and lets an abandoned session self-heal: one stranded mid-pipeline (e.g. by
     * a backend restart, a queue-full dispatch rejection, or the pre-fix {@code @Transactional} race)
     * is superseded by a fresh Apply after {@link #STALE_SESSION_WINDOW} rather than blocking that job
     * forever.
     */
    private boolean isLiveInFlight(ApplicationSubmissionSession s) {
        if (SubmissionStateMachine.isTerminal(s.getStatus())) return false;
        if (ApplicationSubmissionSession.STATUS_WAITING_APPROVAL.equals(s.getStatus())) return true;
        Instant since = s.getCreatedAt() != null ? s.getCreatedAt() : s.getStartedAt();
        return since != null && since.isAfter(Instant.now().minus(STALE_SESSION_WINDOW));
    }

    public Optional<ApplicationSubmissionSession> find(UUID sessionId, UUID userId) {
        return sessions.findByIdAndUserId(sessionId, userId);
    }

    public List<ApplicationSubmissionSession> history(UUID userId, UUID jobId) {
        return sessions.findByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId);
    }

    /** All of a user's submission sessions, newest first — backs the "My submissions" detail list. */
    public List<ApplicationSubmissionSession> recentForUser(UUID userId) {
        return sessions.findByUserIdOrderByCreatedAtDesc(userId);
    }

    public List<ApplicationSubmissionSession> queue() {
        return sessions.findByStatusInOrderByCreatedAtDesc(
                List.of(ApplicationSubmissionSession.STATUS_WAITING_APPROVAL, ApplicationSubmissionSession.STATUS_SUBMITTING));
    }

    /**
     * Guided Apply — the candidate explicitly confirms they completed the employer's application
     * themselves. This is the ONLY way {@link ApplicationSubmissionSession#STATUS_USER_REPORTED_SUBMITTED}
     * is ever reached (see {@link SubmissionStateMachine}) — no timer, no inference from the user
     * opening the employer URL, no automatic transition. Legal only from {@code
     * WAITING_MANUAL_SUBMISSION}; any other current status is a 409-shaped refusal via {@link
     * IllegalStateException}, the same convention {@code WorkflowService#resume} already uses for
     * "action not valid in this state" (see CLAUDE.md).
     *
     * <p>Deliberately does not reuse {@code SUBMITTED}/{@code SUBMIT_UNVERIFIED}: those mean
     * CareerPilot's own automation acted. This status means CareerPilot never touched the employer's
     * form — the candidate is the only witness, and the label must not overstate what is known.
     *
     * <p><b>Atomic conditional UPDATE, not read-then-write.</b> The same class of race Action 2
     * closed for {@code ApprovalQueueRepository.claimDecision}: a double-tap on "Yes, I submitted
     * it", or two open tabs, could otherwise both pass a read-then-check and both write — the
     * second silently overwriting the first's note/timestamp. Only one caller's UPDATE can match
     * {@code WHERE status = 'WAITING_MANUAL_SUBMISSION'}; the loser gets an honest 409 instead.
     *
     * @return empty when the session doesn't exist or isn't owned by {@code userId}
     */
    public Optional<ApplicationSubmissionSession> reportUserSubmitted(UUID userId, UUID sessionId, String note) {
        // Existence/ownership check only — the actual state transition is the atomic claim below,
        // so a session found here can still legitimately fail the claim (already reported, or
        // advanced elsewhere) without this read ever being the arbiter.
        if (sessions.findByIdAndUserId(sessionId, userId).isEmpty()) return Optional.empty();

        Instant now = Instant.now();
        String normalisedNote = (note == null || note.isBlank()) ? null : note;
        int claimed = sessions.claimUserReportedSubmitted(sessionId, userId,
                ApplicationSubmissionSession.STATUS_USER_REPORTED_SUBMITTED, now, normalisedNote);
        if (claimed == 0) {
            String currentStatus = sessions.findByIdAndUserId(sessionId, userId)
                    .map(ApplicationSubmissionSession::getStatus).orElse("UNKNOWN");
            throw new IllegalStateException(
                    "session is not awaiting manual submission (status=" + currentStatus + ")");
        }
        ApplicationSubmissionSession session = sessions.findByIdAndUserId(sessionId, userId).orElseThrow();
        publish(session);
        log.info("APP_SUBMISSION USER_REPORTED_SUBMITTED session={} user={}", sessionId, userId);
        return Optional.of(session);
    }

    public List<ApplicationSubmissionAnswer> answersFor(UUID sessionId) {
        return answers.findBySessionIdOrderByCreatedAtAsc(sessionId);
    }

    // ── Orchestration (steps 1-8, runs on the bounded executor) ──

    /**
     * Package-visible for tests: runs steps 1-8 synchronously, stopping at
     * WAITING_APPROVAL/SUBMITTING. Deliberately NOT {@code @Transactional} — this method is only
     * ever invoked via {@code this.runPipeline(...)} from inside an {@code executor.execute(...)}
     * lambda in {@link #start}, i.e. self-invocation, which bypasses Spring's proxy-based AOP
     * entirely (an annotation here would be silently ignored). Each step below already delegates
     * to a properly-transactional service call ({@code jobValidation}, {@code tailoringService},
     * {@code packageService}, {@code reviewPipeline}, ...); wrapping the whole method in one
     * transaction would also be the wrong design regardless, since it would hold a DB connection
     * open across multiple slow external AI Gateway calls spanning tens of seconds each.
     */
    public void runPipeline(UUID sessionId) {
        ApplicationSubmissionSession session = sessions.findById(sessionId).orElse(null);
        if (session == null) return;
        UUID userId = session.getUserId();
        UUID jobId = session.getJobId();
        try {
            // Step 1 — job validation.
            advance(session, ApplicationSubmissionSession.STATUS_VALIDATING);
            JobValidationService.ValidationResult validation = jobValidation.validate(jobId);
            if (!validation.valid()) {
                fail(session, "job validation failed: " + String.join("; ", validation.reasons()));
                return;
            }
            Job job = validation.job();

            // Step 2 — resume tailoring + cover letter + package assembly (idempotent — reuses existing).
            ResumeTailoring tailoring = tailoringService.latest(userId, jobId)
                    .or(() -> tailoringService.tailor(userId, jobId, null))
                    .orElse(null);
            coverLetterService.latest(userId, jobId)
                    .or(() -> tailoring == null ? Optional.empty()
                            : coverLetterService.generate(userId, jobId, tailoring.getId()));
            ApplicationPackage pkg = packageService.latest(userId, jobId)
                    .or(() -> packageService.assemble(userId, jobId))
                    .orElse(null);
            if (pkg == null) {
                fail(session, "unable to assemble an application package (no tailored resume)");
                return;
            }
            session.setResumeTailoringId(tailoring != null ? tailoring.getId() : null);
            session.setApplicationPackageId(pkg.getId());
            advance(session, ApplicationSubmissionSession.STATUS_PACKAGE_READY);

            // Step 3 — AI review (direct call, not the event chain, since the user is waiting).
            ApplicationReview review = reviewPipeline.latest(pkg.getId())
                    .or(() -> reviewPipeline.review(pkg.getId(), session.getCorrelationId()))
                    .orElse(null);
            session.setApplicationReviewId(review != null ? review.getId() : null);
            advance(session, ApplicationSubmissionSession.STATUS_REVIEW_READY);

            // Step 4 — company brief (best effort; company intel may be disabled — not a hard failure).
            String companyBrief = null;
            if (companyKnowledgeService.isEnabled() && job.getCompany() != null) {
                CompanyKnowledge knowledge = companyKnowledgeService.findByName(userId, job.getCompany()).orElse(null);
                if (knowledge != null) {
                    session.setCompanyKnowledgeId(knowledge.getId());
                    companyBrief = knowledge.getKnowledge();
                }
            }
            advance(session, ApplicationSubmissionSession.STATUS_COMPANY_READY);

            // Step 5 — STAR story recommendations.
            StarStory bestStory = null;
            if (storyEngine.isEnabled()) {
                bestStory = storyEngine.recommend(userId, job.getCompany(), job.getTitle(), null, 1).stream()
                        .findFirst()
                        .flatMap(rec -> starStories.findById(rec.getStarStoryId()))
                        .orElse(null);
            }
            advance(session, ApplicationSubmissionSession.STATUS_STAR_READY);

            // Step 6 — field mapping + question detection/answer generation. The approval screen
            // must always have something to show, but a same-job repeat Apply should not pay for
            // 11 fresh AI Gateway calls when nothing that could change the answers has changed —
            // see ApplicationReuseResolver.
            fieldMapping.map(userId);
            UUID starStoryId = bestStory != null ? bestStory.getId() : null;
            session.setStarStoryId(starStoryId);
            session.setJobFingerprint(JobFingerprint.of(job));

            ApplicationReuseResolver.Basis basis = new ApplicationReuseResolver.Basis(
                    session.getResumeTailoringId(), session.getApplicationPackageId(),
                    session.getCompanyKnowledgeId(), starStoryId);
            ApplicationReuseResolver.Decision decision = reuseResolver.resolve(userId, jobId, session.getId(), basis);
            session.setAnswersReuseDecision(decision.reason().name());

            if (decision.reason() == AnswerReuseDecision.REUSED) {
                ApplicationSubmissionSession source = decision.sourceSession().orElseThrow();
                session.setAnswersReusedFromSessionId(source.getId());
                for (ApplicationSubmissionAnswer prior : answers.findBySessionIdOrderByCreatedAtAsc(source.getId())) {
                    answers.save(ApplicationSubmissionAnswer.builder()
                            .sessionId(session.getId())
                            .questionText(prior.getQuestionText())
                            .questionCategory(prior.getQuestionCategory())
                            .answerText(prior.getAnswerText())
                            .sourceRefs(prior.getSourceRefs())
                            .build());
                }
                log.info("APP_SUBMISSION_REUSE answers.reused=true session={} from={} job={}",
                        session.getId(), source.getId(), jobId);
            } else {
                String resumeText = tailoring != null ? tailoring.getTailoredResumeText() : null;
                AnswerContext ctx = new AnswerContext(resumeText, pkg.getMetadata(), companyBrief, bestStory);
                for (Map.Entry<String, QuestionCategory> q : questionDetection.commonQuestions()) {
                    GeneratedAnswer generated = answerGeneration.generate(userId, jobId, q.getKey(), q.getValue(), ctx);
                    answers.save(ApplicationSubmissionAnswer.builder()
                            .sessionId(session.getId())
                            .questionText(generated.questionText())
                            .questionCategory(generated.category().name())
                            .answerText(generated.answerText())
                            .sourceRefs(generated.sourceRefs())
                            .build());
                }
                log.info("APP_SUBMISSION_REUSE answers.reused=false reason={} session={} job={}",
                        decision.reason(), session.getId(), jobId);
            }
            advance(session, ApplicationSubmissionSession.STATUS_READY_FOR_SUBMISSION);

            // Step 7 — resolve submission strategy via the existing safe autopilot provider registry.
            String applyUrl = JobValidationService.applyUrl(job);
            String provider = providerRegistry.providerNameFor(applyUrl);
            session.setProvider(provider);
            sessions.save(session);

            // Step 8 — mandatory human approval, when enabled for this flow.
            if (approvalEnabled) {
                SafetyResult safety = safetyEngine.evaluate(userId, jobId, pkg.getId());
                Optional<ApprovalQueueEntry> entry = approvalService.enqueue(userId, jobId, pkg.getId(), safety.verdict().name());
                if (entry.isEmpty()) {
                    fail(session, "approval enqueue failed");
                    return;
                }
                session.setApprovalQueueEntryId(entry.get().getId());
                advance(session, ApplicationSubmissionSession.STATUS_WAITING_APPROVAL);
                return; // stop here — resumed by onApprovalGranted()
            }

            // No approval gate configured for this flow — proceed straight to submission.
            advance(session, ApplicationSubmissionSession.STATUS_SUBMITTING);
            submitAndTrack(session, job);
        } catch (Exception e) {
            log.warn("APP_SUBMISSION pipeline error session={}: {}", sessionId, e.toString());
            fail(session, "pipeline error: " + e);
        }
    }

    // ── Steps 9-13 (resumed on human approval) ──

    /**
     * {@code AFTER_COMMIT} — matches this codebase's established worker convention (see
     * resumetailoring/execution workers): {@link ApprovalService#approve} publishes
     * {@link ApprovalGrantedEvent} mid-transaction, so a plain {@code @EventListener} would fire
     * before that transaction commits and dispatch async work racing against uncommitted state.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onApprovalGranted(ApprovalGrantedEvent event) {
        if (!enabled || !approvalEnabled) return;
        sessions.findByApprovalQueueEntryId(event.approvalId()).ifPresent(session -> {
            UUID sessionId = session.getId();
            try {
                executor.execute(() -> continueAfterApproval(sessionId));
            } catch (Exception e) {
                log.warn("APP_SUBMISSION approval-resume dispatch rejected session={}: {}", sessionId, e.toString());
            }
        });
    }

    /**
     * Package-visible for tests: runs steps 9-13 synchronously. Deliberately NOT
     * {@code @Transactional} — same self-invocation reasoning as {@link #runPipeline}.
     */
    public void continueAfterApproval(UUID sessionId) {
        ApplicationSubmissionSession session = sessions.findById(sessionId).orElse(null);
        if (session == null) return;
        if (!ApplicationSubmissionSession.STATUS_WAITING_APPROVAL.equals(session.getStatus())) return;
        Job job = jobs.findById(session.getJobId()).orElse(null);
        if (job == null) {
            fail(session, "job no longer exists");
            return;
        }
        try {
            advance(session, ApplicationSubmissionSession.STATUS_SUBMITTING);
            submitAndTrack(session, job);
        } catch (Exception e) {
            log.warn("APP_SUBMISSION resume error session={}: {}", sessionId, e.toString());
            fail(session, "resume error: " + e);
        }
    }

    // ── Steps 9-13 shared body ──

    private void submitAndTrack(ApplicationSubmissionSession session, Job job) {
        UUID userId = session.getUserId();
        UUID jobId = session.getJobId();

        // Step 9 — execute (direct call so the session can await the synchronous result).
        Optional<ApplicationExecution> execution = executionService.execute(userId, jobId, session.getApplicationPackageId());
        if (execution.isPresent()) {
            session.setApplicationExecutionId(execution.get().getId());
        }
        String executionStatus = execution.map(ApplicationExecution::getExecutionStatus).orElse(null);
        boolean executionFailed = ApplicationExecution.STATUS_FAILED.equals(executionStatus);
        if (executionFailed) {
            fail(session, "execution failed: " + execution.get().getFailureReason());
            return;
        }

        // Step 10 — truthfulness gate (Phase 7.16.5): only advance to SUBMITTED when the
        // underlying ApplicationExecution genuinely reached SUBMITTED (a real ATSConnector or
        // Playwright guest-apply click actually happened). Every other outcome — ABORTED (no
        // execution backend configured, or a connector present but not guest-apply-eligible),
        // AWAITING_APPROVAL (Gap D's own form-screenshot approval still pending, not yet clicked),
        // or no execution row at all (execution engine disabled) — means no company ever received
        // this application. Those cases stop at WAITING_MANUAL_SUBMISSION: the package is real and
        // ready, but the user must complete submission themselves. This intentionally replaces the
        // prior "treat ABORTED as SUBMITTED, matching autopilot's safe semantics" behavior, which
        // mislabeled the vast majority of runs (every ATS except Greenhouse/Lever) as submitted.
        //
        // Phase 0 (Browser Automation Platform) — a third outcome now exists. SUBMIT_UNVERIFIED
        // means the click genuinely happened but the evidence didn't certify delivery. That must
        // NOT fall into the WAITING_MANUAL_SUBMISSION branch below: telling the user to submit
        // manually an application that may already be with the employer is how duplicates are
        // created. It gets its own honest state and proceeds to tracking.
        boolean genuinelySubmitted = ApplicationExecution.STATUS_SUBMITTED.equals(executionStatus);
        boolean submittedUnverified = ApplicationExecution.STATUS_SUBMIT_UNVERIFIED.equals(executionStatus);
        if (submittedUnverified) {
            advance(session, ApplicationSubmissionSession.STATUS_SUBMIT_UNVERIFIED);
            trackAndComplete(session, userId, jobId);
            return;
        }
        if (!genuinelySubmitted) {
            advance(session, ApplicationSubmissionSession.STATUS_WAITING_MANUAL_SUBMISSION);
            return;
        }
        advance(session, ApplicationSubmissionSession.STATUS_SUBMITTED);

        // Step 11 — verify (Phase 7.16.1): VERIFIED is now gated behind real evidence rather than
        // a bare transition. The linked ApplicationExecution row already carries the verification
        // result — SubmissionVerificationService populated it as part of Step 9's execute() call
        // (see ApplicationExecutionService.finalizeGuestApplySubmit), so no second verification
        // call happens here; this step only reads the evidence back and reflects it in the
        // session's own state, same "no fabrication" contract as before, now actually enforced.
        advance(session, ApplicationSubmissionSession.STATUS_VERIFYING);
        ApplicationExecution exec = execution.orElse(null);
        boolean hasEvidence = exec != null
                && "VERIFIED".equals(exec.getVerificationStatus())
                && exec.getConfirmationNumber() != null;
        advance(session, hasEvidence
                ? ApplicationSubmissionSession.STATUS_VERIFIED
                : ApplicationSubmissionSession.STATUS_VERIFICATION_FAILED);

        trackAndComplete(session, userId, jobId);
    }

    /**
     * Steps 12-13, extracted unchanged in Phase 0 so both terminal submit outcomes — verified
     * {@code SUBMITTED} and unproven {@code SUBMIT_UNVERIFIED} — share one tracking path. An
     * unverified submission still needs the kanban card and the lifecycle transition: the
     * application very likely does exist, and hiding it would be its own kind of dishonesty.
     */
    private void trackAndComplete(ApplicationSubmissionSession session, UUID userId, UUID jobId) {
        // Step 12 — tracking via the canonical lifecycle service, plus the kanban Application row so
        // the completed submission actually lands in the "Applied" column. Update the existing card
        // if one exists (e.g. the job was saved first); otherwise CREATE one — a native pipeline Apply
        // on a discovered job has no prior kanban row, and without this the submission would complete
        // invisibly (the exact "nothing landed anywhere" gap seen in testing).
        try {
            lifecycleService.transition(userId, jobId, ApplicationLifecycle.STATUS_SUBMITTED, "application-submission pipeline");
        } catch (Exception e) {
            log.warn("APP_SUBMISSION lifecycle transition failed session={}: {}", session.getId(), e.toString());
        }
        try {
            Application existing = applications.findFirstByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId).orElse(null);
            if (existing != null) {
                session.setApplicationId(existing.getId());
                applicationService.updateStatus(userId, existing.getId(), "APPLIED", "submitted via application-submission pipeline");
            } else {
                UUID orgId = users.findById(userId).map(User::getOrgId).orElse(null);
                if (orgId != null) {
                    Application created = applicationService.create(userId, orgId, Application.builder()
                            .userId(userId).jobId(jobId).resumeId(session.getResumeId())
                            .status("APPLIED")
                            .notes("submitted via application-submission pipeline")
                            .build());
                    session.setApplicationId(created.getId());
                } else {
                    log.warn("APP_SUBMISSION no orgId for user={}, kanban card not created session={}", userId, session.getId());
                }
            }
        } catch (Exception e) {
            log.warn("APP_SUBMISSION kanban application upsert failed session={}: {}", session.getId(), e.toString());
        }
        advance(session, ApplicationSubmissionSession.STATUS_TRACKING);

        // Step 13 — learning capture.
        learningPipeline.capture(LearningEventType.APPLICATION_SUBMITTED, session.getCorrelationId(),
                userId, jobId, session.getResumeTailoringId() == null ? null : session.getResumeTailoringId().toString(),
                session.getId().toString());
        session.setCompletedAt(Instant.now());
        advance(session, ApplicationSubmissionSession.STATUS_COMPLETED);
    }

    // ── Stranded-session recovery ──

    /**
     * Statuses a stranded session may safely be failed from: every stage strictly BEFORE any submit
     * attempt. A session stuck here provably never reached an employer — no execute() call has run —
     * so {@code FAILED} is an honest verdict.
     *
     * <p>Deliberately excludes everything from {@code SUBMITTING} onwards. Once a submit attempt has
     * begun, the application may genuinely be with the employer, and relabelling that row
     * {@code FAILED} would tell the user their application failed when it may have succeeded — the
     * same fabrication {@code VerificationAdjudicator} and {@code STATUS_SUBMIT_UNVERIFIED} exist to
     * prevent. Those rows are counted and logged for human attention instead.
     *
     * <p>Also excludes {@code WAITING_APPROVAL} (a human may legitimately take days) and
     * {@code WAITING_MANUAL_SUBMISSION} (a documented dead end resting on a user action).
     */
    static final List<String> REAPABLE_PRE_SUBMIT_STATUSES = List.of(
            ApplicationSubmissionSession.STATUS_CREATED,
            ApplicationSubmissionSession.STATUS_VALIDATING,
            ApplicationSubmissionSession.STATUS_PACKAGE_READY,
            ApplicationSubmissionSession.STATUS_REVIEW_READY,
            ApplicationSubmissionSession.STATUS_COMPANY_READY,
            ApplicationSubmissionSession.STATUS_STAR_READY,
            ApplicationSubmissionSession.STATUS_READY_FOR_SUBMISSION);

    /**
     * Fail sessions abandoned mid-pipeline, so no session can sit in an intermediate state forever.
     *
     * <p><b>The defect this closes.</b> {@link #runPipeline} is one straight-line method owned by a
     * single thread on the bounded submission executor. Every status transition is written by that
     * thread and nothing else. If the thread disappears — JVM killed mid-pipeline (observed: the
     * process died during an in-flight AI Gateway call with no graceful-shutdown log at all),
     * {@code executor.execute} rejected on a full queue, or an uncaught {@code Error} — the row's
     * status column has no remaining writer. It is not "waiting" for an event; there is no event.
     * The pipeline stops silently and the UI shows a spinner indefinitely.
     *
     * <p>This does not add a stage, an event, or a state: it drives the {@code -> FAILED} edge the
     * state machine already allows from any active status, using the same {@link #fail} path a
     * caught in-pipeline exception uses.
     *
     * @return number of sessions failed
     */
    public int reapStranded(Duration staleAfter) {
        if (!enabled) return 0;
        Instant cutoff = Instant.now().minus(staleAfter);
        List<ApplicationSubmissionSession> stranded =
                sessions.findByStatusInAndUpdatedAtBefore(REAPABLE_PRE_SUBMIT_STATUSES, cutoff);
        int failed = 0;
        for (ApplicationSubmissionSession session : stranded) {
            try {
                fail(session, "stranded: no pipeline progress since " + session.getUpdatedAt()
                        + " (owning thread gone — likely a backend restart mid-pipeline); "
                        + "nothing was submitted to any employer. Re-apply to retry.");
                failed++;
            } catch (Exception e) {
                log.warn("APP_SUBMISSION reap failed session={}: {}", session.getId(), e.toString());
            }
        }
        // Post-submit strandings are NOT auto-failed (see REAPABLE_PRE_SUBMIT_STATUSES) — surfaced
        // so a human can adjudicate rather than silently ignored.
        List<ApplicationSubmissionSession> postSubmit = sessions.findByStatusInAndUpdatedAtBefore(
                List.of(ApplicationSubmissionSession.STATUS_SUBMITTING,
                        ApplicationSubmissionSession.STATUS_SUBMITTED,
                        ApplicationSubmissionSession.STATUS_VERIFYING,
                        ApplicationSubmissionSession.STATUS_VERIFIED,
                        ApplicationSubmissionSession.STATUS_VERIFICATION_FAILED,
                        ApplicationSubmissionSession.STATUS_SUBMIT_UNVERIFIED,
                        ApplicationSubmissionSession.STATUS_TRACKING), cutoff);
        if (!postSubmit.isEmpty()) {
            log.warn("APP_SUBMISSION {} stranded post-submit session(s) need human review (not auto-failed): {}",
                    postSubmit.size(), postSubmit.stream().map(s -> s.getId() + "=" + s.getStatus()).toList());
        }
        if (failed > 0) {
            log.info("APP_SUBMISSION reaped {} stranded pre-submit session(s) older than {}", failed, staleAfter);
        }
        return failed;
    }

    // ── State-machine helpers ──

    private void advance(ApplicationSubmissionSession session, String newStatus) {
        String from = session.getStatus();
        if (!SubmissionStateMachine.canTransition(from, newStatus)) {
            log.warn("APP_SUBMISSION refused illegal transition {} -> {} session={}", from, newStatus, session.getId());
            return;
        }
        session.setStatus(newStatus);
        sessions.save(session);
        publish(session);
        log.info("APP_SUBMISSION {} -> {} session={} user={} job={}",
                from, newStatus, session.getId(), session.getUserId(), session.getJobId());
    }

    private void fail(ApplicationSubmissionSession session, String reason) {
        String from = session.getStatus();
        if (!SubmissionStateMachine.canTransition(from, ApplicationSubmissionSession.STATUS_FAILED)) {
            log.warn("APP_SUBMISSION cannot fail from terminal status={} session={}", from, session.getId());
            return;
        }
        session.setStatus(ApplicationSubmissionSession.STATUS_FAILED);
        session.setFailureReason(reason);
        session.setCompletedAt(Instant.now());
        sessions.save(session);
        publish(session);
        log.info("APP_SUBMISSION FAILED session={} reason={}", session.getId(), reason);
    }

    private void publish(ApplicationSubmissionSession session) {
        try {
            events.publishEvent(new ApplicationSubmissionSessionEvent(
                    session.getId(), session.getUserId(), session.getJobId(), session.getStatus()));
        } catch (Exception ignored) {
            // event publication is best-effort; never fails the pipeline
        }
    }
}
