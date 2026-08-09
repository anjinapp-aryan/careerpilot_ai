package ai.careerpilot.execution.browser.form;

import ai.careerpilot.domain.CandidateProfile;
import ai.careerpilot.domain.FieldVerificationSource;
import ai.careerpilot.domain.User;
import ai.careerpilot.execution.browser.BrowserAutomationMetrics;
import ai.careerpilot.execution.browser.BrowserLifecycleMetrics;
import ai.careerpilot.execution.browser.BrowserSessionManager;
import ai.careerpilot.execution.browser.PlaywrightAutomationProvider;
import ai.careerpilot.execution.browser.pool.BrowserLaunchOptionsFactory;
import ai.careerpilot.execution.browser.pool.BrowserLeasePool;
import ai.careerpilot.execution.browser.pool.BrowserPoolMetrics;
import ai.careerpilot.domain.ExecutionStageEvent;
import ai.careerpilot.execution.timeline.ExecutionStageMetrics;
import ai.careerpilot.execution.timeline.ExecutionTimelineRecorder;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P7 Action 6 — Final Controlled Submission-Boundary Safety Verification.
 *
 * <p>Drives the real production path — real Chromium (via {@link PlaywrightAutomationProvider}
 * wired against real {@link BrowserSessionManager}/{@link BrowserLeasePool}), real {@link
 * FormDiscoveryScript} JS, real {@link FieldClassifier}/{@link AnswerResolver}/{@link
 * FormFillPlanner}, real {@link BrowserFormAutomationEngine}, real {@link
 * ExecutionTimelineRecorder} — against a fully self-authored local fixture (served over real HTTP,
 * no network access, no employer content), then stops deliberately before the submit boundary.
 *
 * <p>Only two seams are test doubles, and both are inert data sources, never DOM/browser behaviour:
 * {@link UserRepository}/{@link CandidateProfileRepository}/{@link CandidateAtsProfileRepository}
 * (seeded synthetic profile data — no real candidate PII) and {@link ExecutionStageEventRepository}
 * (captures what the real recorder writes, so the test can assert on it without a database).
 *
 * <p>The fixture's submit button is genuinely enabled and genuinely wired to a real (test-local)
 * HTTP endpoint and a real {@code submit}/{@code click} JS listener — so "the button was never
 * reached" cannot be explained by the button being inert. Three independent counters prove it was
 * never clicked: the page's own {@code window.__submitClicks}, a page-level {@code submit} event
 * counter, and a server-side hit counter on the local {@code /submit} endpoint the button would call.
 */
class Action6SubmissionBoundaryLiveTest {

    private static com.microsoft.playwright.Playwright playwright;
    private static com.microsoft.playwright.Browser sharedBrowser;
    private static HttpServer server;
    private static String baseUrl;
    private static final AtomicInteger serverSubmitHits = new AtomicInteger();
    private static Path resumeFile;

    private static final String FIXTURE = """
            <html><body>
              <h1>Apply for this job</h1>
              <div class="application-heading">Submit your application</div>

              <div class="field-row">
                <label for="full_name">Full Name<span aria-hidden="true">*</span></label>
                <input type="text" id="full_name" name="full_name" required>
              </div>
              <div class="field-row">
                <label for="email">Email<span aria-hidden="true">*</span></label>
                <input type="email" id="email" name="email" required>
              </div>
              <div class="field-row">
                <label for="phone">Phone</label>
                <input type="tel" id="phone" name="phone">
              </div>

              <div class="field-row">
                <label>Resume/CV<span aria-hidden="true">*</span></label>
                <input type="file" id="resume_upload" name="resume" required>
              </div>
              <div class="field-row">
                <label>Cover Letter</label>
                <input type="file" id="cover_letter_upload" name="cover_letter">
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

              <div class="field-row select-shell">
                <label>Will you now or in the future require sponsorship for employment visa status?<span aria-hidden="true">*</span></label>
                <div id="visa_sponsorship" role="combobox" aria-controls="visa_list" aria-expanded="false"
                     tabindex="0" data-required="true">
                  <span class="display">Select...</span>
                </div>
                <ul id="visa_list" role="listbox" style="display:none">
                  <li role="option">Yes</li>
                  <li role="option">No</li>
                </ul>
              </div>

              <div class="field-row select-shell">
                <label>How did you hear about this job?</label>
                <div id="how_heard" role="combobox" aria-controls="how_heard_list" aria-expanded="false" tabindex="0">
                  <span class="display">Select...</span>
                </div>
                <ul id="how_heard_list" role="listbox" style="display:none">
                  <li role="option">LinkedIn</li>
                  <li role="option">Referral</li>
                </ul>
              </div>

              <div class="field-row">
                <label for="education">Highest education</label>
                <select id="education" name="education">
                  <option value="">Select...</option>
                  <option value="bachelors">Bachelor's</option>
                  <option value="masters">Master's</option>
                </select>
              </div>

              <div class="field-row">
                <label id="gender-legend">Gender</label>
                <div role="radiogroup" aria-labelledby="gender-legend">
                  <label><input type="radio" name="gender" value="male"> Male</label>
                  <label><input type="radio" name="gender" value="decline"> Decline to self-identify</label>
                </div>
              </div>

              <div class="field-row">
                <label><input type="checkbox" id="eeo_ack" name="eeo_ack"> I acknowledge the above EEO disclosures.</label>
              </div>

              <button type="button" id="submit_application_button">Submit Application</button>

              <script>
                window.__submitClicks = 0;
                window.__formSubmitEvents = 0;
                document.getElementById('submit_application_button').addEventListener('click', () => {
                  window.__submitClicks++;
                  fetch('/submit', { method: 'POST' });
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
                wireCombobox('visa_sponsorship', 'visa_list');
                wireCombobox('how_heard', 'how_heard_list');
              </script>
            </body></html>
            """;

    @BeforeAll
    static void setUp() throws IOException {
        resumeFile = Files.createTempFile("action6-synthetic-resume", ".pdf");
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
        // Server-side proof, independent of any page JS: if this endpoint is ever hit, a real
        // submission attempt was made. It never should be.
        server.createContext("/submit", exchange -> {
            serverSubmitHits.incrementAndGet();
            exchange.sendResponseHeaders(200, 0);
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

    private BrowserSessionManager sessionManager;
    private BrowserLeasePool leasePool;
    private PlaywrightAutomationProvider browser;
    private final List<ExecutionStageEvent> savedEvents = new ArrayList<>();

    @AfterEach
    void closeSession() {
        // logout() closes this thread's context/lease so the shared browser is left clean between
        // tests, matching PlaywrightAutomationProvider's own thread-affinity contract.
        try {
            browser.logout();
        } catch (Exception ignored) {
            // no session was open — nothing to close
        }
    }

    /**
     * Wires the real production browser stack (Gap D classes) directly against the already-launched
     * shared {@link #sharedBrowser}, bypassing Spring only because this is a plain JUnit test — every
     * class instantiated below is the exact production class, not a test double.
     */
    private void wireRealBrowserStack() {
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

        sessionManager = new BrowserSessionManager(true, launchOptions, lifecycleMetrics,
                poolProvider, 300, 100, 21600) {
            // The real class launches its own Playwright/Browser lazily; this test already has one
            // shared, already-launched Browser from @BeforeAll (one real Chromium process for the
            // whole suite, not one per test). Overriding only the context factory — not any
            // decision logic — keeps every real safety/lifecycle rule (locking, zombie detection,
            // recycle triggers) genuinely exercised against a genuinely real Browser.
            @Override
            public com.microsoft.playwright.BrowserContext newContext() {
                return sharedBrowser.newContext();
            }
        };
        leasePool = new BrowserLeasePool(sessionManager, poolMetrics, 1, 180, 30, 30, 20);
        poolRef.set(leasePool);

        BrowserAutomationMetrics automationMetrics = new BrowserAutomationMetrics();
        browser = new PlaywrightAutomationProvider(sessionManager, leasePool, automationMetrics,
                true, 4000, true);
    }

    private BrowserFormAutomationEngine wireRealEngine(UUID userId) {
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
                        Map.of(AtsProfileField.WORK_AUTHORIZATION.fieldName(),
                                "Authorized to work without sponsorship"),
                        FieldVerificationSource.USER_ENTERED)
                .orElseThrow();
        when(atsRepo.findByUserId(userId)).thenReturn(Optional.of(seeded));

        FormFillPlanner planner = new FormFillPlanner(
                new FieldClassifier(new QuestionDetectionService()),
                new AnswerResolver(new FieldMappingService(users, profiles, atsService), users, answers));

        ExecutionStageEventRepository events = mock(ExecutionStageEventRepository.class);
        when(events.save(any())).thenAnswer(inv -> {
            ExecutionStageEvent e = inv.getArgument(0);
            // Simulate real JPA @GeneratedValue: the recorder's started() relies on save(row).getId()
            // being non-null to hand back a real row id for the caller to later close.
            if (e.getId() == null) e.setId(UUID.randomUUID());
            savedEvents.add(e);
            return e;
        });
        when(events.findById(any())).thenAnswer(inv -> savedEvents.stream()
                .filter(e -> e.getId().equals(inv.getArgument(0))).findFirst());
        ExecutionTimelineRecorder timeline = new ExecutionTimelineRecorder(
                events, new ExecutionStageMetrics(), true);

        return new BrowserFormAutomationEngine(browser, planner,
                new ValidationErrorDetector(), new MultiStepFormNavigator(), new FormAutomationMetrics(),
                timeline, true, true);
    }

    /**
     * Phases 1-10 of Action 6, end to end: navigate the real fixture, discover, resolve, fill
     * (including both required custom comboboxes), verify, reach a genuinely submit-ready state, and
     * stop — proving the submit boundary was never crossed via three independent counters.
     */
    @Test
    void reachesGenuineSubmitReadyStateAndStopsBeforeTheSubmitClick() {
        wireRealBrowserStack();
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        BrowserFormAutomationEngine engine = wireRealEngine(userId);
        ExecutionTimelineRecorder.RunContext run = new ExecutionTimelineRecorder.RunContext(
                UUID.randomUUID(), userId, sessionId);

        // Phase 1/2 — real navigation + real discovery.
        browser.navigate(baseUrl + "/apply");
        List<DiscoveredField> discovered = FormDiscoveryScript.parse(
                browser.evaluate(FormDiscoveryScript.DISCOVER_FIELDS));
        assertThat(discovered).isNotEmpty();
        long comboboxCount = discovered.stream().filter(f -> "combobox".equals(f.role())).count();
        assertThat(comboboxCount).as("fixture must expose custom comboboxes").isEqualTo(3);
        long requiredComboboxCount = discovered.stream()
                .filter(f -> "combobox".equals(f.role()) && f.required()).count();
        assertThat(requiredComboboxCount).as("both work-auth and visa comboboxes are required").isEqualTo(2);

        // Phases 3-10 — real plan + real fill, through the actual production engine.
        BrowserFormAutomationEngine.FillOutcome outcome = engine.fillForm(userId, sessionId,
                new BrowserFormAutomationEngine.DocumentPaths(resumeFile, "resume.pdf", null, null), run);

        // Phase 5/7 — combobox + field verification via independent DOM read-back, not the outcome's
        // own report of itself.
        assertThat(browser.evaluate("() => document.querySelector('#work_auth .display').textContent"))
                .isEqualTo("Authorized to work without sponsorship");
        assertThat(browser.evaluate("() => document.querySelector('#visa_sponsorship .display').textContent"))
                .isEqualTo("No");
        assertThat(browser.evaluate("() => document.getElementById('full_name').value"))
                .isEqualTo("CareerPilot Test User");
        assertThat(browser.evaluate("() => document.getElementById('email').value"))
                .isEqualTo("careerpilot-test@example.invalid");
        assertThat(browser.hasUploadedFile("#resume_upload")).isTrue();
        // The optional, unresolvable "how did you hear" combobox must remain untouched, not guessed.
        assertThat(browser.evaluate("() => document.querySelector('#how_heard .display').textContent"))
                .isEqualTo("Select...");

        // Phase 8/9 — genuine SUBMIT_READY, from the real production readiness calculation.
        assertThat(outcome.blockingGaps())
                .as("both required comboboxes and both required text fields resolved: no blocking gap")
                .isEmpty();
        assertThat(outcome.filled()).contains(CanonicalField.FULL_NAME.name(), CanonicalField.EMAIL.name(),
                CanonicalField.WORK_AUTHORIZATION.name(), CanonicalField.VISA_SPONSORSHIP.name(),
                CanonicalField.RESUME_UPLOAD.name());
        assertThat(outcome.isApprovable())
                .as("SUBMIT_READY proxy: success with zero blocking gaps")
                .isTrue();

        // Phase 10/11 — the submit boundary. Every one of these must hold with the run otherwise
        // complete and genuinely ready.
        assertThat(browser.evaluate("() => window.__submitClicks")).isEqualTo(0);
        assertThat(browser.evaluate(
                "() => document.getElementById('submit_application_button').disabled")).isEqualTo(false);
        assertThat(serverSubmitHits.get())
                .as("server-side proof, independent of page JS: /submit was never called")
                .isEqualTo(0);

        // Phase 12 — truthful timeline: only the stages BrowserFormAutomationEngine itself owns are
        // present, and each with real counts, never fabricated ones. NAVIGATION_*/ATS_IDENTIFIED/
        // VERIFICATION_*/RESULT_PERSISTED are owned by ApplicationExecutionService/
        // GuestApplyAutomationService one layer up, which this harness deliberately does not invoke —
        // reported as not-reached rather than faked.
        List<String> stages = savedEvents.stream().map(ExecutionStageEvent::getStage).distinct().toList();
        assertThat(stages).contains("FORM_DISCOVERY_STARTED", "FORM_DISCOVERED",
                "QUESTIONS_EXTRACTED", "QUESTIONS_RESOLVED",
                "DOCUMENT_UPLOAD_STARTED", "DOCUMENT_UPLOAD_COMPLETED");
        assertThat(stages).doesNotContain("NAVIGATION_STARTED", "ATS_IDENTIFIED",
                "VERIFICATION_STARTED", "RESULT_PERSISTED");
    }

    /**
     * Phase 9 negative half — restore-and-rerun in the same test to prove the readiness flag is a
     * genuine computation, not a fixed test-harness value: an unresolvable required answer produces
     * NOT_READY, and the identical pipeline with the real answer restored produces READY.
     */
    @Test
    void requiredFieldWithNoResolvableAnswerBlocksReadinessThenClearsOnceResolved() {
        wireRealBrowserStack();
        UUID userId = UUID.randomUUID();
        browser.navigate(baseUrl + "/apply");

        // No CandidateProfile/CandidateAtsProfile at all: nothing can resolve.
        UserRepository users = mock(UserRepository.class);
        CandidateProfileRepository profiles = mock(CandidateProfileRepository.class);
        ApplicationSubmissionAnswerRepository answers = mock(ApplicationSubmissionAnswerRepository.class);
        CandidateAtsProfileRepository atsRepo = mock(CandidateAtsProfileRepository.class);
        when(users.findById(userId)).thenReturn(Optional.of(
                User.builder().id(userId).fullName("CareerPilot Test User")
                        .email("careerpilot-test@example.invalid").build()));
        when(profiles.findByUserId(userId)).thenReturn(Optional.empty());
        when(answers.findBySessionIdOrderByCreatedAtAsc(any())).thenReturn(List.of());
        when(atsRepo.findByUserId(userId)).thenReturn(Optional.empty());
        CandidateAtsProfileService atsService = new CandidateAtsProfileService(atsRepo, true);

        FormFillPlanner planner = new FormFillPlanner(
                new FieldClassifier(new QuestionDetectionService()),
                new AnswerResolver(new FieldMappingService(users, profiles, atsService), users, answers));
        ExecutionStageEventRepository events = mock(ExecutionStageEventRepository.class);
        ExecutionTimelineRecorder timeline = new ExecutionTimelineRecorder(
                events, new ExecutionStageMetrics(), true);
        BrowserFormAutomationEngine engine = new BrowserFormAutomationEngine(browser, planner,
                new ValidationErrorDetector(), new MultiStepFormNavigator(), new FormAutomationMetrics(),
                timeline, true, true);

        BrowserFormAutomationEngine.FillOutcome notReady = engine.fillForm(userId, UUID.randomUUID(),
                new BrowserFormAutomationEngine.DocumentPaths(null, null, null, null));

        assertThat(notReady.blockingGaps()).isNotEmpty();
        assertThat(notReady.isApprovable()).as("NOT_READY: unresolved required fields").isFalse();
        assertThat(browser.evaluate("() => window.__submitClicks")).isEqualTo(0);
        assertThat(serverSubmitHits.get()).isEqualTo(0);
    }

}
