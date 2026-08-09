package ai.careerpilot.execution.browser;

import ai.careerpilot.domain.ApplicationExecution;
import ai.careerpilot.domain.ApprovalQueueEntry;
import ai.careerpilot.domain.ExecutionScreenshot;
import ai.careerpilot.domain.Job;
import ai.careerpilot.execution.approval.ApprovalService;
import ai.careerpilot.execution.ats.ATSConnector;
import ai.careerpilot.repo.ExecutionScreenshotRepository;
import ai.careerpilot.repo.UserRepository;
import ai.careerpilot.storage.S3StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Phase 7.16.3 — the Screenshot Timeline: BEFORE_SUBMIT (pre-existing), AFTER_SUBMIT and FAILURE (new). */
class GuestApplyAutomationServiceTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();
    private final UUID execId = UUID.randomUUID();
    private final UUID pkgId = UUID.randomUUID();

    private PlaywrightAutomationProvider browser;
    private BrowserAutomationMetrics metrics;
    private S3StorageService storage;
    private ApprovalService approvalService;
    private ExecutionScreenshotRepository screenshots;
    private UserRepository users;
    private GuestApplyAutomationService service;
    /**
     * Phase 12C — a disabled form engine, so every pre-existing assertion in this file keeps
     * measuring exactly what it did before. The engine's own behaviour is covered by its dedicated
     * suites; {@code formEngineBlockingGapAbortsBeforeAnyApproval} below covers the integration.
     */
    private ai.careerpilot.execution.browser.form.BrowserFormAutomationEngine formEngine;

    @BeforeEach
    void setUp() {
        browser = mock(PlaywrightAutomationProvider.class);
        metrics = new BrowserAutomationMetrics();
        storage = mock(S3StorageService.class);
        approvalService = mock(ApprovalService.class);
        screenshots = mock(ExecutionScreenshotRepository.class);
        users = mock(UserRepository.class);
        formEngine = mock(ai.careerpilot.execution.browser.form.BrowserFormAutomationEngine.class);
        when(formEngine.isEnabled()).thenReturn(false);
        service = new GuestApplyAutomationService(browser, metrics, storage, approvalService, screenshots, users,
                formEngine,
                mock(ai.careerpilot.repo.ApplicationPackageRepository.class),
                mock(ai.careerpilot.repo.ResumeRepository.class),
                mock(ai.careerpilot.repo.CoverLetterRepository.class),
                mock(ai.careerpilot.repo.ApplicationSubmissionSessionRepository.class),
                // Phase F3: absent orchestrator => single-page behaviour, unchanged.
                mock(org.springframework.beans.factory.ObjectProvider.class),
                // P5: recorder present but disabled — instrumentation must not alter any outcome.
                new ai.careerpilot.execution.timeline.ExecutionTimelineRecorder(
                        mock(ai.careerpilot.repo.ExecutionStageEventRepository.class),
                        new ai.careerpilot.execution.timeline.ExecutionStageMetrics(), false),
                true);

        when(browser.currentPageHtml()).thenReturn("<html><body>a normal apply form</body></html>");
        when(storage.uploadBytes(any(), anyString(), anyString(), anyString())).thenReturn("some/s3/key.png");
        when(screenshots.save(any())).thenAnswer(inv -> {
            ExecutionScreenshot s = inv.getArgument(0);
            if (s.getId() == null) s.setId(UUID.randomUUID());
            return s;
        });
    }

    private ApplicationExecution exec() {
        return ApplicationExecution.builder()
                .id(execId).userId(userId).jobId(jobId).applicationPackageId(pkgId).attemptCount(1)
                .build();
    }

    private ATSConnector connector(String name) {
        ATSConnector c = mock(ATSConnector.class);
        when(c.name()).thenReturn(name);
        when(c.extractForm(any())).thenReturn(Map.of());
        return c;
    }

    private Job job() {
        return Job.builder().id(jobId).title("Eng").company("Acme").sourceUrl("https://example.com/apply").build();
    }

    // ── Phase 12C — universal form engine integration ──────────────────────────────────────────

    private void enableFormEngineWith(
            ai.careerpilot.execution.browser.form.BrowserFormAutomationEngine.FillOutcome outcome) {
        when(formEngine.isEnabled()).thenReturn(true);
        when(formEngine.fillForm(any(), any(), any())).thenReturn(outcome);
        // P7 Action 4 — finalizeSubmit calls the new 4-arg (timeline-aware) overload; attemptFill
        // still calls the pre-existing 3-arg one. Stubbing both keeps this helper usable by tests
        // exercising either path without each test needing to know which overload its own call site uses.
        when(formEngine.fillForm(any(), any(), any(), any())).thenReturn(outcome);
    }

    /**
     * The single most important safety behaviour this phase adds. A form whose required resume
     * field cannot be filled must NOT reach the human approval queue: asking someone to approve a
     * screenshot of an incomplete form invites them to approve a submission that either fails the
     * employer's own validation or is delivered incomplete under the candidate's real name.
     */
    @Test
    void aBlockingGapAbortsBeforeAnyApprovalIsEnqueued() {
        enableFormEngineWith(new ai.careerpilot.execution.browser.form.BrowserFormAutomationEngine.FillOutcome(
                true, true, null, java.util.List.of("EMAIL"), java.util.Map.of(),
                java.util.List.of("Resume: no tailored resume available in the application package"),
                java.util.Map.of()));

        GuestApplyAutomationService.AttemptOutcome outcome = service.attemptFill(exec(), job(), connector("greenhouse"));

        assertThat(outcome.kind()).isEqualTo(GuestApplyAutomationService.AttemptOutcome.Kind.ABORTED);
        assertThat(outcome.reason()).contains("could not be filled from verified data");
        verify(approvalService, never()).enqueueFormScreenshot(any(), any(), any(), any(), any(), anyString());
    }

    /** The same refusal at finalize time, where the click would be irreversible. */
    @Test
    void aBlockingGapAtFinalizeRefusesToSubmit() {
        ATSConnector connector = connector("greenhouse");
        enableFormEngineWith(new ai.careerpilot.execution.browser.form.BrowserFormAutomationEngine.FillOutcome(
                true, true, null, java.util.List.of(), java.util.Map.of(),
                java.util.List.of("Resume: no tailored resume available"), java.util.Map.of()));

        GuestApplyAutomationService.AttemptOutcome outcome = service.finalizeSubmit(exec(), job(), connector);

        assertThat(outcome.kind()).isEqualTo(GuestApplyAutomationService.AttemptOutcome.Kind.ABORTED);
        verify(connector, never()).submit(any(), any());
    }

    @Test
    void aCleanFormEngineRunStillReachesTheApprovalGate() {
        enableFormEngineWith(new ai.careerpilot.execution.browser.form.BrowserFormAutomationEngine.FillOutcome(
                true, true, null, java.util.List.of("EMAIL", "RESUME_UPLOAD"), java.util.Map.of(),
                java.util.List.of(), java.util.Map.of()));
        when(approvalService.enqueueFormScreenshot(any(), any(), any(), any(), any(), anyString()))
                .thenReturn(Optional.of(ApprovalQueueEntry.builder().id(UUID.randomUUID()).build()));

        GuestApplyAutomationService.AttemptOutcome outcome = service.attemptFill(exec(), job(), connector("greenhouse"));

        // Still parks for human approval — this phase widens what can be filled, never what can be sent.
        assertThat(outcome.kind()).isEqualTo(GuestApplyAutomationService.AttemptOutcome.Kind.AWAITING_APPROVAL);
    }

    // ── P7 Action 7 — Execution Visibility: attemptFill must record real timeline stages, not just
    // finalizeSubmit. Real (not disabled) `ExecutionTimelineRecorder`, real `ExecutionStageEventRepository`
    // mock, so what gets persisted is verified directly rather than assuming the wiring compiles. ──

    private GuestApplyAutomationService serviceWithEnabledRecorder(
            ai.careerpilot.repo.ExecutionStageEventRepository events) {
        ai.careerpilot.execution.timeline.ExecutionTimelineRecorder recorder =
                new ai.careerpilot.execution.timeline.ExecutionTimelineRecorder(
                        events, new ai.careerpilot.execution.timeline.ExecutionStageMetrics(), true);
        return new GuestApplyAutomationService(browser, metrics, storage, approvalService, screenshots, users,
                formEngine,
                mock(ai.careerpilot.repo.ApplicationPackageRepository.class),
                mock(ai.careerpilot.repo.ResumeRepository.class),
                mock(ai.careerpilot.repo.CoverLetterRepository.class),
                mock(ai.careerpilot.repo.ApplicationSubmissionSessionRepository.class),
                mock(org.springframework.beans.factory.ObjectProvider.class),
                recorder, true);
    }

    @Test
    void attemptFillOnCaptchaDetectionRecordsNavigationCompletedThenFailedPageClassified() {
        ai.careerpilot.repo.ExecutionStageEventRepository events = mock(ai.careerpilot.repo.ExecutionStageEventRepository.class);
        // findById must return the same row `started()` produced, so `completed()`/`failed()` can
        // close it — captured in this map rather than stubbed statically.
        java.util.Map<UUID, ai.careerpilot.domain.ExecutionStageEvent> saved = new java.util.HashMap<>();
        when(events.save(any())).thenAnswer(inv -> {
            ai.careerpilot.domain.ExecutionStageEvent row = inv.getArgument(0);
            if (row.getId() == null) row.setId(UUID.randomUUID());
            saved.put(row.getId(), row);
            return row;
        });
        when(events.findById(any())).thenAnswer(inv -> Optional.ofNullable(saved.get(inv.getArgument(0))));

        when(browser.currentPageHtml()).thenReturn("<html><body><div class=\"g-recaptcha\"></div></body></html>");
        GuestApplyAutomationService svc = serviceWithEnabledRecorder(events);

        GuestApplyAutomationService.AttemptOutcome outcome = svc.attemptFill(exec(), job(), connector("greenhouse"));

        assertThat(outcome.kind()).isEqualTo(GuestApplyAutomationService.AttemptOutcome.Kind.ABORTED);
        java.util.List<String> stages = saved.values().stream()
                .map(ai.careerpilot.domain.ExecutionStageEvent::getStage).toList();
        assertThat(stages).contains("NAVIGATION_STARTED", "NAVIGATION_COMPLETED", "PAGE_CLASSIFIED");
        ai.careerpilot.domain.ExecutionStageEvent pageClassified = saved.values().stream()
                .filter(e -> "PAGE_CLASSIFIED".equals(e.getStage())).findFirst().orElseThrow();
        assertThat(pageClassified.getStatus()).isEqualTo(ai.careerpilot.domain.ExecutionStageEvent.STATUS_FAILED);
        assertThat(pageClassified.getReason()).contains("captcha or login wall detected");
        // Never reached field-fill — a real automation stop, not a manufactured one.
        assertThat(stages).doesNotContain("FIELD_FILL_STARTED");
    }

    // ── P0.2 — iframe-aware CAPTCHA hard-stop in production execution ──

    @Test
    @DisplayName("6/7: CAPTCHA only inside a same-origin iframe aborts before any field is filled or uploaded")
    void attemptFillAbortsOnIframeOnlyCaptchaBeforeAnyFillOrUpload() {
        // Top document is clean — only the frame-aware probe would catch this, proving the fix is
        // load-bearing rather than redundant with the pre-existing top-level check.
        when(browser.currentPageHtml()).thenReturn("<html><body>clean top document</body></html>");
        java.util.Map<String, Object> frame = new java.util.LinkedHashMap<>();
        frame.put("framePath", "iframe:nth-of-type(1)");
        frame.put("frameUrl", "https://example.com/challenge");
        frame.put("title", "Verify");
        frame.put("fileInputs", 0);
        frame.put("emailInputs", 0);
        frame.put("textInputs", 0);
        frame.put("submitButtons", 0);
        frame.put("applicationHeading", false);
        frame.put("passwordInputs", false);
        frame.put("captchaDetected", true);
        java.util.Map<String, Object> envelope = new java.util.LinkedHashMap<>();
        envelope.put("frames", java.util.List.of(frame));
        envelope.put("inaccessible", java.util.List.of());
        when(browser.evaluate(ai.careerpilot.execution.browser.form.FormDiscoveryScript.DISCOVER_FRAME_REPORT))
                .thenReturn(envelope);
        enableFormEngineWith(new ai.careerpilot.execution.browser.form.BrowserFormAutomationEngine.FillOutcome(
                true, true, null, java.util.List.of("EMAIL", "RESUME_UPLOAD"), java.util.Map.of(),
                java.util.List.of(), java.util.Map.of()));

        GuestApplyAutomationService.AttemptOutcome outcome =
                service.attemptFill(exec(), job(), connector("greenhouse"));

        assertThat(outcome.kind()).isEqualTo(GuestApplyAutomationService.AttemptOutcome.Kind.ABORTED);
        verify(browser, never()).fillForm(any());
        verify(formEngine, never()).fillForm(any(), any(), any());
        verify(formEngine, never()).fillForm(any(), any(), any(), any());
        verify(approvalService, never()).enqueueFormScreenshot(any(), any(), any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("8: CAPTCHA only inside a same-origin iframe on resubmit aborts before the submit click")
    void finalizeSubmitAbortsOnIframeOnlyCaptchaBeforeSubmit() {
        ATSConnector connector = connector("greenhouse");
        when(browser.currentPageHtml()).thenReturn("<html><body>clean top document</body></html>");
        java.util.Map<String, Object> frame = new java.util.LinkedHashMap<>();
        frame.put("framePath", "iframe:nth-of-type(1)");
        frame.put("frameUrl", "https://example.com/challenge");
        frame.put("title", "Verify");
        frame.put("fileInputs", 0);
        frame.put("emailInputs", 0);
        frame.put("textInputs", 0);
        frame.put("submitButtons", 0);
        frame.put("applicationHeading", false);
        frame.put("passwordInputs", false);
        frame.put("captchaDetected", true);
        java.util.Map<String, Object> envelope = new java.util.LinkedHashMap<>();
        envelope.put("frames", java.util.List.of(frame));
        envelope.put("inaccessible", java.util.List.of());
        when(browser.evaluate(ai.careerpilot.execution.browser.form.FormDiscoveryScript.DISCOVER_FRAME_REPORT))
                .thenReturn(envelope);

        GuestApplyAutomationService.AttemptOutcome outcome = service.finalizeSubmit(exec(), job(), connector);

        assertThat(outcome.kind()).isEqualTo(GuestApplyAutomationService.AttemptOutcome.Kind.ABORTED);
        verify(connector, never()).submit(any(), any());
        verify(browser, never()).fillForm(any());
    }

    @Test
    void aFormEngineFailureDegradesToTheConnectorOnlyBehaviour() {
        when(formEngine.isEnabled()).thenReturn(true);
        // P7 Action 4 — attemptFill's internal call now always goes through runFormEngine's single
        // call site, which passes a (null, for attemptFill) RunContext into the 4-arg overload.
        // Stubbing only the 3-arg matcher here would leave the real call unstubbed (Mockito default:
        // returns null), which happens to degrade to the same outcome via a NullPointerException
        // instead of the IllegalStateException this test means to exercise — stubbing both keeps the
        // test honest about which failure it's actually proving.
        when(formEngine.fillForm(any(), any(), any())).thenThrow(new IllegalStateException("engine exploded"));
        when(formEngine.fillForm(any(), any(), any(), any())).thenThrow(new IllegalStateException("engine exploded"));
        when(approvalService.enqueueFormScreenshot(any(), any(), any(), any(), any(), anyString()))
                .thenReturn(Optional.of(ApprovalQueueEntry.builder().id(UUID.randomUUID()).build()));

        GuestApplyAutomationService.AttemptOutcome outcome = service.attemptFill(exec(), job(), connector("greenhouse"));

        // Exactly the Phase 12B outcome: the connector's own fields were filled and the run parks.
        assertThat(outcome.kind()).isEqualTo(GuestApplyAutomationService.AttemptOutcome.Kind.AWAITING_APPROVAL);
    }

    @Test
    void withTheEngineDisabledItIsNeverInvoked() {
        when(approvalService.enqueueFormScreenshot(any(), any(), any(), any(), any(), anyString()))
                .thenReturn(Optional.of(ApprovalQueueEntry.builder().id(UUID.randomUUID()).build()));

        service.attemptFill(exec(), job(), connector("greenhouse"));

        verify(formEngine, never()).fillForm(any(), any(), any());
        verify(formEngine, never()).fillForm(any(), any(), any(), any());
    }

    @Test
    void attemptFillCapturesBeforeSubmitScreenshot() {
        ATSConnector connector = connector("greenhouse");
        when(approvalService.enqueueFormScreenshot(any(), any(), any(), any(), any(), anyString()))
                .thenReturn(Optional.of(ApprovalQueueEntry.builder().id(UUID.randomUUID()).build()));

        service.attemptFill(exec(), job(), connector);

        ArgumentCaptor<ExecutionScreenshot> captor = ArgumentCaptor.forClass(ExecutionScreenshot.class);
        verify(screenshots, times(2)).save(captor.capture()); // once on insert, once after approvalQueueEntryId is set
        assertThat(captor.getAllValues().get(0).getPhase()).isEqualTo("BEFORE_SUBMIT");
    }

    @Test
    void finalizeSubmitCapturesAfterSubmitScreenshotOnSuccess() {
        ATSConnector connector = connector("greenhouse");
        when(connector.submit(any(), any())).thenReturn("confirmation-page-html");

        service.finalizeSubmit(exec(), job(), connector);

        ArgumentCaptor<ExecutionScreenshot> captor = ArgumentCaptor.forClass(ExecutionScreenshot.class);
        verify(screenshots).save(captor.capture());
        assertThat(captor.getValue().getPhase()).isEqualTo("AFTER_SUBMIT");
    }

    /**
     * P4 WI1 — DELIBERATELY INVERTED. This previously asserted {@code Kind.ERROR}, i.e. "the submit
     * failed, retry it". A throw from {@code connector.submit(...)} happens at or after the click,
     * so the application may already exist at the employer; reporting it as a retryable error is
     * how the same application gets sent twice. The screenshot assertion is unchanged.
     */
    @Test
    void finalizeSubmitCapturesFailureScreenshotOnPostClickFailure() {
        ATSConnector connector = connector("greenhouse");
        when(connector.submit(any(), any())).thenThrow(new RuntimeException("ats rejected"));

        var outcome = service.finalizeSubmit(exec(), job(), connector);

        assertThat(outcome.kind())
                .isEqualTo(GuestApplyAutomationService.AttemptOutcome.Kind.SUBMIT_UNVERIFIED);
        assertThat(outcome.reason()).contains("submit click was issued");
        ArgumentCaptor<ExecutionScreenshot> captor = ArgumentCaptor.forClass(ExecutionScreenshot.class);
        verify(screenshots).save(captor.capture());
        assertThat(captor.getValue().getPhase()).isEqualTo("FAILURE");
    }

    @Test
    void failureScreenshotCaptureNeverThrowsEvenIfBrowserHasNoPage() {
        ATSConnector connector = connector("greenhouse");
        when(connector.submit(any(), any())).thenThrow(new RuntimeException("ats rejected"));
        doThrowOnCaptureScreenshot();

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> service.finalizeSubmit(exec(), job(), connector));
    }

    private void doThrowOnCaptureScreenshot() {
        org.mockito.Mockito.doThrow(new IllegalStateException("no active page"))
                .when(browser).captureScreenshot(any(Path.class));
    }

    // ── P4 WI1 — the click/no-click boundary ─────────────────────────────────────────────────

    @Test
    void aFailureBeforeTheClickIsStillAnOrdinaryRetryableError() {
        // Navigation threw, so nothing was ever submitted. This must stay ERROR: downgrading every
        // failure to "unverified" would strand genuinely-failed attempts that are safe to retry.
        ATSConnector connector = connector("greenhouse");
        org.mockito.Mockito.doThrow(new RuntimeException("net::ERR_CONNECTION_RESET"))
                .when(browser).navigate(any());

        var outcome = service.finalizeSubmit(exec(), job(), connector);

        assertThat(outcome.kind()).isEqualTo(GuestApplyAutomationService.AttemptOutcome.Kind.ERROR);
    }

    @Test
    void theBrowserLeaseIsReleasedOnEveryPostClickFailure() {
        ATSConnector connector = connector("greenhouse");
        when(connector.submit(any(), any()))
                .thenThrow(new RuntimeException("Execution context was destroyed"));

        service.finalizeSubmit(exec(), job(), connector);

        // A validation or submit that keeps the single permit starves every later execution.
        verify(browser).logout();
    }
}
