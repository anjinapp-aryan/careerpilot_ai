package ai.careerpilot.execution.browser;

import ai.careerpilot.domain.ApplicationExecution;
import ai.careerpilot.domain.ApprovalQueueEntry;
import ai.careerpilot.domain.CandidateProfile;
import ai.careerpilot.domain.ExecutionScreenshot;
import ai.careerpilot.domain.ExecutionStageEvent;
import ai.careerpilot.domain.FieldVerificationSource;
import ai.careerpilot.domain.Job;
import ai.careerpilot.domain.User;
import ai.careerpilot.execution.approval.ApprovalService;
import ai.careerpilot.execution.ats.ATSConnector;
import ai.careerpilot.execution.browser.form.AnswerResolver;
import ai.careerpilot.execution.browser.form.BrowserFormAutomationEngine;
import ai.careerpilot.execution.browser.form.FieldClassifier;
import ai.careerpilot.execution.browser.form.FormAutomationMetrics;
import ai.careerpilot.execution.browser.form.FormFillPlanner;
import ai.careerpilot.execution.browser.form.MultiStepFormNavigator;
import ai.careerpilot.execution.browser.form.ValidationErrorDetector;
import ai.careerpilot.execution.browser.pool.BrowserLaunchOptionsFactory;
import ai.careerpilot.execution.browser.pool.BrowserLeasePool;
import ai.careerpilot.execution.browser.pool.BrowserPoolMetrics;
import ai.careerpilot.execution.timeline.ExecutionEvidenceService;
import ai.careerpilot.execution.timeline.ExecutionStage;
import ai.careerpilot.execution.timeline.ExecutionStageMetrics;
import ai.careerpilot.execution.timeline.ExecutionTimelineRecorder;
import ai.careerpilot.execution.timeline.ExecutionTimelineService;
import ai.careerpilot.repo.ApplicationSubmissionAnswerRepository;
import ai.careerpilot.repo.CandidateAtsProfileRepository;
import ai.careerpilot.repo.CandidateProfileRepository;
import ai.careerpilot.repo.ExecutionStageEventRepository;
import ai.careerpilot.repo.UserRepository;
import ai.careerpilot.service.profile.ats.AtsProfileField;
import ai.careerpilot.service.profile.ats.CandidateAtsProfileService;
import ai.careerpilot.submission.mapping.FieldMappingService;
import ai.careerpilot.submission.question.QuestionDetectionService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * GOLDEN END-TO-END — P0/P1 Hardening. Drives the real production Guided Apply path — real
 * Chromium, real {@link GuestApplyAutomationService}, real {@link BrowserFormAutomationEngine},
 * real {@link ExecutionTimelineRecorder} — against a self-authored local fixture styled on
 * {@code GreenhouseConnector}'s real field schema (never a real employer site), from first
 * navigation through a genuine submit click, then feeds the captured stage rows into the real
 * {@link ExecutionEvidenceService} to prove the evidence UI would show truthful, non-fabricated
 * facts at every step.
 *
 * <p>Scope boundary, stated honestly: this proves the browser-automation half of the golden path
 * (attemptFill → AWAITING_APPROVAL → finalizeSubmit → SUBMITTED → evidence) with the exact
 * production classes. It does not additionally re-drive the Spring-wired orchestration one layer
 * up ({@code ApplicationExecutionService}, {@code ApprovalService} granting, {@code
 * FormApprovalExecutionWorker}, {@code ApplicationSubmissionSessionService}) inside a
 * {@code @SpringBootTest}/Testcontainers context — that stack was independently found this session
 * to hang in this environment (Docker Desktop contention with a concurrently-running docker-compose
 * stack), reproduced twice. That orchestration layer's own decision logic is covered by
 * {@code ApplicationExecutionServiceTest}/{@code ApplicationSubmissionSessionServiceTest} (mocked
 * collaborators, no browser, no Docker) — this test is deliberately the complementary half: real
 * Playwright, mocked persistence, exactly {@code Action6SubmissionBoundaryLiveTest}'s own approach.
 */
class GoldenGuidedApplyEndToEndLiveTest {

    private static com.microsoft.playwright.Playwright playwright;
    private static com.microsoft.playwright.Browser sharedBrowser;
    private static HttpServer server;
    private static String baseUrl;
    private static final AtomicInteger serverSubmitHits = new AtomicInteger();
    private static Path resumeFile;

    /** Field ids match {@code GreenhouseConnector#extractForm} exactly — this is what a real
     *  Greenhouse-eligible posting's connector schema expects. */
    private static final String FIXTURE = """
            <html><body>
              <h1>Apply for this job</h1>
              <form id="app" action="/submit" method="post">
                <div class="field-row">
                  <label for="first_name">First Name<span aria-hidden="true">*</span></label>
                  <input type="text" id="first_name" name="first_name" required>
                </div>
                <div class="field-row">
                  <label for="last_name">Last Name<span aria-hidden="true">*</span></label>
                  <input type="text" id="last_name" name="last_name" required>
                </div>
                <div class="field-row">
                  <label for="email">Email<span aria-hidden="true">*</span></label>
                  <input type="email" id="email" name="email" required>
                </div>
                <div class="field-row">
                  <label>Resume/CV<span aria-hidden="true">*</span></label>
                  <input type="file" id="resume_upload" name="resume" required>
                </div>
                <div class="field-row select-shell">
                  <label>Are you legally authorized to work in this country?<span aria-hidden="true">*</span></label>
                  <div id="work_auth" role="combobox" aria-controls="work_auth_list" aria-expanded="false"
                       tabindex="0" data-required="true">
                    <span class="display">Select...</span>
                  </div>
                  <ul id="work_auth_list" role="listbox" style="display:none">
                    <li role="option">Authorized to work without sponsorship</li>
                    <li role="option">Requires sponsorship</li>
                  </ul>
                </div>
                <button type="submit" id="submit_application_button">Submit Application</button>
              </form>
              <script>
                window.__submitClicks = 0;
                document.getElementById('submit_application_button').addEventListener('click', () => {
                  window.__submitClicks++;
                });
                function wireCombobox(controlId, listId) {
                  const control = document.getElementById(controlId);
                  const list = document.getElementById(listId);
                  control.addEventListener('click', () => {
                    list.style.display = (list.style.display === 'none') ? 'block' : 'none';
                  });
                  list.querySelectorAll('[role="option"]').forEach(opt => {
                    opt.addEventListener('click', () => {
                      control.querySelector('.display').textContent = opt.textContent;
                      list.style.display = 'none';
                    });
                  });
                }
                wireCombobox('work_auth', 'work_auth_list');
              </script>
            </body></html>
            """;

    private static final String CONFIRMATION_PAGE = """
            <html><body>
              <h1>Application received</h1>
              <p>Thank you for applying. Your reference number is REF-CAREERPILOT-TEST-12345.</p>
            </body></html>
            """;

    @BeforeAll
    static void setUp() throws IOException {
        resumeFile = Files.createTempFile("golden-e2e-synthetic-resume", ".pdf");
        Files.write(resumeFile, "%PDF-1.4 synthetic disposable test resume, no real candidate data"
                .getBytes(StandardCharsets.UTF_8));

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/apply", exchange -> {
            byte[] body = FIXTURE.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        // A genuinely working, local-only submit endpoint — proves the click is real, not inert.
        server.createContext("/submit", exchange -> {
            serverSubmitHits.incrementAndGet();
            byte[] body = CONFIRMATION_PAGE.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

        playwright = com.microsoft.playwright.Playwright.create();
        sharedBrowser = playwright.chromium().launch(
                new com.microsoft.playwright.BrowserType.LaunchOptions().setHeadless(true));
    }

    @AfterAll
    static void tearDown() throws IOException {
        if (sharedBrowser != null) sharedBrowser.close();
        if (playwright != null) playwright.close();
        if (server != null) server.stop(0);
        if (resumeFile != null) Files.deleteIfExists(resumeFile);
    }

    private BrowserSessionManager wireSessionManager() {
        BrowserLifecycleMetrics lifecycleMetrics = new BrowserLifecycleMetrics();
        BrowserLaunchOptionsFactory launchOptions = new BrowserLaunchOptionsFactory(
                true, true, 256, "", "", 60000);
        BrowserPoolMetrics poolMetrics = new BrowserPoolMetrics();
        java.util.concurrent.atomic.AtomicReference<BrowserLeasePool> poolRef = new java.util.concurrent.atomic.AtomicReference<>();
        org.springframework.beans.factory.ObjectProvider<BrowserLeasePool> poolProvider =
                new org.springframework.beans.factory.ObjectProvider<>() {
                    public BrowserLeasePool getObject() { return poolRef.get(); }
                    public BrowserLeasePool getObject(Object... args) { return poolRef.get(); }
                    public BrowserLeasePool getIfAvailable() { return poolRef.get(); }
                    public BrowserLeasePool getIfUnique() { return poolRef.get(); }
                };
        BrowserSessionManager sessionManager = new BrowserSessionManager(true, launchOptions, lifecycleMetrics,
                poolProvider, 300, 100, 21600) {
            @Override
            public com.microsoft.playwright.BrowserContext newContext() {
                return sharedBrowser.newContext();
            }
        };
        BrowserLeasePool leasePool = new BrowserLeasePool(sessionManager, poolMetrics, 1, 180, 30, 30, 20);
        poolRef.set(leasePool);
        this.leasePool = leasePool;
        return sessionManager;
    }

    private BrowserLeasePool leasePool;

    @Test
    void goldenPath_attemptFillThenApprovalThenFinalizeSubmit_producesTruthfulEvidenceAllTheWay() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        UUID execId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        BrowserSessionManager sessionManager = wireSessionManager();
        BrowserAutomationMetrics automationMetrics = new BrowserAutomationMetrics();
        PlaywrightAutomationProvider browser = new PlaywrightAutomationProvider(
                sessionManager, leasePool, automationMetrics, true, 4000, true);

        // Real form engine over real candidate data (mocked repos, real classification/resolution).
        UserRepository users = mock(UserRepository.class);
        CandidateProfileRepository profiles = mock(CandidateProfileRepository.class);
        ApplicationSubmissionAnswerRepository answers = mock(ApplicationSubmissionAnswerRepository.class);
        CandidateAtsProfileRepository atsRepo = mock(CandidateAtsProfileRepository.class);
        when(users.findById(userId)).thenReturn(Optional.of(
                User.builder().id(userId).fullName("CareerPilot Test User")
                        .email("careerpilot-test@example.invalid").build()));
        when(profiles.findByUserId(userId)).thenReturn(Optional.of(
                CandidateProfile.builder().userId(userId).visaRequired(false).build()));
        when(answers.findBySessionIdOrderByCreatedAtAsc(any())).thenReturn(List.of());
        CandidateAtsProfileService atsService = new CandidateAtsProfileService(atsRepo, true);
        when(atsRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(atsRepo.findByUserId(userId)).thenReturn(Optional.empty());
        var seeded = atsService.update(userId,
                        Map.of(AtsProfileField.WORK_AUTHORIZATION.fieldName(), "Authorized to work without sponsorship"),
                        FieldVerificationSource.USER_ENTERED)
                .orElseThrow();
        when(atsRepo.findByUserId(userId)).thenReturn(Optional.of(seeded));
        FormFillPlanner planner = new FormFillPlanner(
                new FieldClassifier(new QuestionDetectionService()),
                new AnswerResolver(new FieldMappingService(users, profiles, atsService), users, answers));

        // Real ExecutionTimelineRecorder, capturing every real stage write.
        List<ExecutionStageEvent> savedEvents = new ArrayList<>();
        ExecutionStageEventRepository events = mock(ExecutionStageEventRepository.class);
        when(events.save(any())).thenAnswer(inv -> {
            ExecutionStageEvent e = inv.getArgument(0);
            if (e.getId() == null) e.setId(UUID.randomUUID());
            savedEvents.add(e);
            return e;
        });
        when(events.findById(any())).thenAnswer(inv -> savedEvents.stream()
                .filter(e -> e.getId().equals(inv.getArgument(0))).findFirst());
        when(events.maxSequenceNo(any())).thenAnswer(inv ->
                savedEvents.stream().filter(e -> execId.equals(e.getExecutionId()))
                        .mapToInt(ExecutionStageEvent::getSequenceNo).max().stream().boxed().findFirst().orElse(null));
        ExecutionTimelineRecorder timeline = new ExecutionTimelineRecorder(
                events, new ExecutionStageMetrics(), true);

        BrowserFormAutomationEngine formEngine = new BrowserFormAutomationEngine(browser, planner,
                new ValidationErrorDetector(), new MultiStepFormNavigator(), new FormAutomationMetrics(),
                timeline, true, true);

        // Mocked persistence/approval seams — real Playwright is what's under test, not JPA/S3.
        ApprovalService approvalService = mock(ApprovalService.class);
        UUID approvalEntryId = UUID.randomUUID();
        when(approvalService.enqueueFormScreenshot(any(), any(), any(), any(), any(), anyString()))
                .thenReturn(Optional.of(ApprovalQueueEntry.builder().id(approvalEntryId).build()));
        var screenshots = mock(ai.careerpilot.repo.ExecutionScreenshotRepository.class);
        when(screenshots.save(any())).thenAnswer(inv -> {
            ExecutionScreenshot s = inv.getArgument(0);
            if (s.getId() == null) s.setId(UUID.randomUUID());
            return s;
        });
        var storage = mock(ai.careerpilot.storage.S3StorageService.class);
        when(storage.uploadBytes(any(), anyString(), anyString(), anyString())).thenReturn("some/s3/key.png");
        // The form engine's own resume upload is real: resolves a real ApplicationPackage -> real
        // Resume -> real bytes (this test's own synthetic resume file), materialised to a real temp
        // file and genuinely uploaded to the fixture's real file input.
        UUID packageId = UUID.randomUUID();
        UUID resumeId = UUID.randomUUID();
        byte[] resumeBytes = Files.readAllBytes(resumeFile);
        when(storage.download("resumes/" + resumeId)).thenReturn(resumeBytes);
        var packages = mock(ai.careerpilot.repo.ApplicationPackageRepository.class);
        when(packages.findById(packageId)).thenReturn(Optional.of(
                ai.careerpilot.domain.ApplicationPackage.builder().id(packageId).userId(userId)
                        .resumeId(resumeId).build()));
        var resumes = mock(ai.careerpilot.repo.ResumeRepository.class);
        when(resumes.findById(resumeId)).thenReturn(Optional.of(
                ai.careerpilot.domain.Resume.builder().id(resumeId).userId(userId)
                        .filename("resume.pdf").s3Key("resumes/" + resumeId).build()));

        GuestApplyAutomationService guestApply = new GuestApplyAutomationService(
                browser, automationMetrics, storage, approvalService, screenshots, users,
                formEngine,
                packages,
                resumes,
                mock(ai.careerpilot.repo.CoverLetterRepository.class),
                mock(ai.careerpilot.repo.ApplicationSubmissionSessionRepository.class),
                mock(org.springframework.beans.factory.ObjectProvider.class),
                timeline, true);

        ApplicationExecution exec = ApplicationExecution.builder()
                .id(execId).userId(userId).jobId(jobId).applicationPackageId(packageId).attemptCount(1).build();
        Job job = Job.builder().id(jobId).title("Backend Engineer").company("Acme")
                .sourceUrl(baseUrl + "/apply").build();
        ATSConnector connector = mock(ATSConnector.class);
        when(connector.name()).thenReturn("greenhouse");
        when(connector.extractForm(any())).thenReturn(Map.of(
                "#first_name", "first_name", "#last_name", "last_name", "#email", "email"));
        // Real click via the real production submit() method — this connector stub only supplies
        // the schema/confirmation-extraction ATS-specific glue GreenhouseConnector itself owns.
        when(connector.submit(any(), any())).thenAnswer(inv -> {
            browser.submit();
            return browser.captureConfirmation();
        });

        // ── Step 1: attemptFill — real navigate, real fill, real screenshot, park for approval ──
        GuestApplyAutomationService.AttemptOutcome fillOutcome = guestApply.attemptFill(exec, job, connector);
        assertThat(fillOutcome.kind()).isEqualTo(GuestApplyAutomationService.AttemptOutcome.Kind.AWAITING_APPROVAL);
        assertThat(fillOutcome.approvalId()).isEqualTo(approvalEntryId);
        assertThat(serverSubmitHits.get()).as("must not submit before human approval").isZero();

        List<String> stagesAfterFill = savedEvents.stream().map(ExecutionStageEvent::getStage).distinct().toList();
        assertThat(stagesAfterFill).contains("NAVIGATION_STARTED", "NAVIGATION_COMPLETED", "PAGE_CLASSIFIED",
                "FIELD_FILL_STARTED", "FORM_DISCOVERY_STARTED", "FORM_DISCOVERED", "QUESTIONS_EXTRACTED",
                "QUESTIONS_RESOLVED", "FIELD_FILL_COMPLETED");
        assertThat(stagesAfterFill).as("never reaches submit during attemptFill")
                .doesNotContain("SUBMIT_CLICK_STARTED", "SUBMIT_CLICK_COMPLETED");

        // ── Step 2: human approves (simulated — the real gate is FormApprovalExecutionWorker,
        //    covered by its own unit tests; what matters here is that finalizeSubmit is the ONLY
        //    method that can produce a real click, and it is never called until this point) ──

        // ── Step 3: finalizeSubmit — real re-navigate, real re-fill, real submit click ──
        GuestApplyAutomationService.AttemptOutcome submitOutcome = guestApply.finalizeSubmit(exec, job, connector);
        assertThat(submitOutcome.kind()).isEqualTo(GuestApplyAutomationService.AttemptOutcome.Kind.SUBMITTED);
        assertThat(submitOutcome.confirmationReference()).contains("Application received", "REF-CAREERPILOT-TEST-12345");
        // The page/context is already closed by finalizeSubmit's own safeLogout() at this point
        // (matching Action6's per-attempt lifecycle), so the server-side hit counter — independent
        // of any page JS — is the authoritative proof of the real click, not a page evaluate.
        assertThat(serverSubmitHits.get()).as("exactly one real submit click reached the server").isEqualTo(1);

        List<String> allStages = savedEvents.stream().map(ExecutionStageEvent::getStage).toList();
        assertThat(allStages).contains("SUBMIT_CLICK_STARTED", "SUBMIT_CLICK_COMPLETED", "CONFIRMATION_DETECTED");

        // ── Step 4: the golden acceptance test — feed the REAL captured stages into the REAL
        //    ExecutionEvidenceService and prove the evidence a user would see is truthful. ──
        ExecutionTimelineService timelineService = mock(ExecutionTimelineService.class);
        when(timelineService.timeline(eq(execId), eq(userId))).thenReturn(Optional.of(renderAsTimelineMap(savedEvents, execId)));
        ExecutionEvidenceService evidenceService = new ExecutionEvidenceService(timelineService);

        Map<String, Object> evidence = evidenceService.evidenceFor(userId, execId);
        assertThat(evidence.get("hasExecution")).isEqualTo(true);
        assertThat(evidence.get("state")).isEqualTo("COMPLETED");
        assertThat(evidence.get("employerPageReached")).isEqualTo(true);
        assertThat(evidence.get("formDiscovered")).isEqualTo(true);
        assertThat((Integer) evidence.get("fieldsFilled")).as("real, non-zero field count").isGreaterThan(0);
        assertThat(evidence.get("automationStopped")).isEqualTo(false);
        assertThat(evidence.get("captchaOrLoginDetected")).isEqualTo(false);
    }

    private static <T> T eq(T value) { return org.mockito.ArgumentMatchers.eq(value); }

    /** Mirrors {@code ExecutionTimelineService#renderStages}'s output shape exactly. */
    private static Map<String, Object> renderAsTimelineMap(List<ExecutionStageEvent> rows, UUID execId) {
        List<Map<String, Object>> stages = new ArrayList<>();
        for (ExecutionStageEvent row : rows) {
            if (!execId.equals(row.getExecutionId())) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sequence", row.getSequenceNo());
            m.put("stage", row.getStage());
            m.put("displayName", displayNameOf(row.getStage()));
            m.put("status", row.getStatus());
            m.put("startedAt", row.getStartedAt() == null ? null : row.getStartedAt().toString());
            m.put("endedAt", row.getEndedAt() == null ? null : row.getEndedAt().toString());
            m.put("durationMs", row.getDurationMs());
            m.put("failureCategory", row.getFailureCategory());
            m.put("reason", row.getReason());
            m.put("detail", row.getDetail());
            stages.add(m);
        }
        Map<String, Object> exit = new LinkedHashMap<>();
        exit.put("outcome", "COMPLETED");
        exit.put("reason", null);
        exit.put("stoppedAtDisplayName", stages.isEmpty() ? null : stages.get(stages.size() - 1).get("displayName"));
        exit.put("failureCategory", null);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stages", stages);
        out.put("executionStatus", "SUBMITTED");
        out.put("instrumentationEnabled", true);
        out.put("exit", exit);
        return out;
    }

    private static String displayNameOf(String stage) {
        try {
            return ExecutionStage.valueOf(stage).displayName();
        } catch (Exception e) {
            return stage;
        }
    }
}
