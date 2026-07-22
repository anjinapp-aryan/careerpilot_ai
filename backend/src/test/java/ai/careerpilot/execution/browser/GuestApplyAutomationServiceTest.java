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

    @BeforeEach
    void setUp() {
        browser = mock(PlaywrightAutomationProvider.class);
        metrics = new BrowserAutomationMetrics();
        storage = mock(S3StorageService.class);
        approvalService = mock(ApprovalService.class);
        screenshots = mock(ExecutionScreenshotRepository.class);
        users = mock(UserRepository.class);
        service = new GuestApplyAutomationService(browser, metrics, storage, approvalService, screenshots, users, true);

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

    @Test
    void finalizeSubmitCapturesFailureScreenshotOnError() {
        ATSConnector connector = connector("greenhouse");
        when(connector.submit(any(), any())).thenThrow(new RuntimeException("ats rejected"));

        var outcome = service.finalizeSubmit(exec(), job(), connector);

        assertThat(outcome.kind()).isEqualTo(GuestApplyAutomationService.AttemptOutcome.Kind.ERROR);
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
}
