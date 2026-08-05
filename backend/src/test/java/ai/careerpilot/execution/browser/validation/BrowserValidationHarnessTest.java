package ai.careerpilot.execution.browser.validation;

import ai.careerpilot.domain.User;
import ai.careerpilot.execution.browser.PlaywrightAutomationProvider;
import ai.careerpilot.execution.browser.form.FieldClassifier;
import ai.careerpilot.execution.browser.form.FormDiscoveryScript;
import ai.careerpilot.execution.browser.form.FormFillPlanner;
import ai.careerpilot.repo.ApplicationSubmissionAnswerRepository;
import ai.careerpilot.repo.CandidateProfileRepository;
import ai.careerpilot.repo.UserRepository;
import ai.careerpilot.storage.S3StorageService;
import ai.careerpilot.submission.mapping.FieldMappingService;
import ai.careerpilot.submission.question.QuestionDetectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 12C.5 — the validation harness.
 *
 * <p>The load-bearing tests here assert that nothing reaches the employer: no upload, no answered
 * question, no submit, no multi-step advance. The structural guarantee is stronger than these
 * assertions — {@code BrowserFormAutomationEngine} is not a dependency of the harness at all, so
 * there is no code path to an interaction — but the tests pin it so a future edit that adds one
 * fails loudly.
 */
class BrowserValidationHarnessTest {

    private final UUID userId = UUID.randomUUID();

    private PlaywrightAutomationProvider browser;
    private S3StorageService storage;
    private BrowserValidationMetrics metrics;
    private FieldClassifier classifier;
    private FormFillPlanner planner;

    @BeforeEach
    void setUp() {
        browser = mock(PlaywrightAutomationProvider.class);
        storage = mock(S3StorageService.class);
        metrics = new BrowserValidationMetrics();

        UserRepository users = mock(UserRepository.class);
        CandidateProfileRepository profiles = mock(CandidateProfileRepository.class);
        ApplicationSubmissionAnswerRepository answers = mock(ApplicationSubmissionAnswerRepository.class);
        when(users.findById(userId)).thenReturn(Optional.of(
                User.builder().id(userId).fullName("Ada Lovelace").email("ada@example.com").build()));
        when(profiles.findByUserId(userId)).thenReturn(Optional.empty());
        when(answers.findBySessionIdOrderByCreatedAtAsc(any())).thenReturn(List.of());

        classifier = new FieldClassifier(new QuestionDetectionService());
        planner = new FormFillPlanner(classifier,
                new ai.careerpilot.execution.browser.form.AnswerResolver(
                        new FieldMappingService(users, profiles, disabledAtsProfiles()), users, answers));

        when(browser.currentPageHtml()).thenReturn("<html><body>apply form</body></html>");
        when(browser.drainConsoleErrors()).thenReturn(List.of());
        when(storage.uploadBytes(any(), anyString(), anyString(), anyString())).thenReturn("some/key.png");
    }

    private BrowserValidationHarness harness(boolean enabled, boolean automationEnabled) {
        return new BrowserValidationHarness(browser, classifier, planner,
                new ValidationUrlPolicy(true, "", true), metrics, storage,
                enabled, automationEnabled, 0, false);
    }

    private static Map<String, Object> control(String selector, String tag, String type,
                                               String label, boolean required) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("selector", selector);
        map.put("tag", tag);
        map.put("type", type);
        map.put("label", label);
        map.put("required", required);
        return map;
    }

    private void discovers(List<Map<String, Object>> controls) {
        when(browser.evaluate(FormDiscoveryScript.DISCOVER_FIELDS)).thenReturn(new ArrayList<>(controls));
        when(browser.evaluate(FormDiscoveryScript.DISCOVER_ENVIRONMENT))
                .thenReturn(Map.of("spaFramework", "React", "iframeCount", 0, "shadowRootCount", 0,
                        "captchaDetected", false, "cookieBannerDetected", false,
                        "failedRequests", 0, "title", "Apply"));
    }

    private static final String URL = "https://boards.greenhouse.io/acme/jobs/1";

    // ── the safety guarantees ──

    @Test
    void nothingIsEverUploadedAnsweredOrSubmitted() {
        discovers(List.of(
                control("#first_name", "input", "text", "First Name", true),
                control("#email", "input", "email", "Email", true),
                control("#resume", "input", "file", "Resume", true)));

        harness(true, true).validate(URL, userId, null, new BrowserValidationHarness.DocumentAvailability(true, false));

        verify(browser, never()).uploadFileTo(anyString(), any());
        verify(browser, never()).uploadResume(any());
        verify(browser, never()).uploadCoverLetter(any());
        verify(browser, never()).submit();
        verify(browser, never()).fillAt(anyString(), anyString());
        verify(browser, never()).fillForm(any());
        verify(browser, never()).selectOptionAt(anyString(), anyString());
        verify(browser, never()).setCheckedAt(anyString(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(browser, never()).clickAt(anyString());
    }

    @Test
    void everyReportStatesExplicitlyThatNothingWasSent() {
        discovers(List.of(control("#email", "input", "email", "Email", false)));
        Map<String, Object> snapshot = harness(true, true)
                .validate(URL, userId, null, BrowserValidationHarness.DocumentAvailability.none()).summary();

        assertThat(snapshot).containsEntry("submitted", false)
                .containsEntry("documentsUploaded", false)
                .containsEntry("questionsAnswered", false);
    }

    @Test
    void theBrowserLeaseIsAlwaysReleasedEvenWhenDiscoveryThrows() {
        // A validation run must never hold the single production browser permit.
        when(browser.evaluate(anyString())).thenThrow(new IllegalStateException("page gone"));

        ValidationReport report = harness(true, true)
                .validate(URL, userId, null, BrowserValidationHarness.DocumentAvailability.none());

        assertThat(report.status()).isEqualTo(ValidationReport.Status.FAILED);
        verify(browser).logout();
    }

    // ── gating ──

    @Test
    void disabledRefusesWithoutOpeningABrowser() {
        ValidationReport report = harness(false, true)
                .validate(URL, userId, null, BrowserValidationHarness.DocumentAvailability.none());

        assertThat(report.status()).isEqualTo(ValidationReport.Status.REFUSED);
        verify(browser, never()).navigate(anyString());
    }

    @Test
    void theMasterAutomationFlagIsAlsoRequired() {
        ValidationReport report = harness(true, false)
                .validate(URL, userId, null, BrowserValidationHarness.DocumentAvailability.none());

        assertThat(report.status()).isEqualTo(ValidationReport.Status.REFUSED);
        verify(browser, never()).navigate(anyString());
    }

    @Test
    void aDisallowedUrlIsRefusedBeforeNavigation() {
        ValidationReport report = harness(true, true)
                .validate("http://127.0.0.1:8080/actuator", userId, null,
                        BrowserValidationHarness.DocumentAvailability.none());

        assertThat(report.status()).isEqualTo(ValidationReport.Status.REFUSED);
        verify(browser, never()).navigate(anyString());
        assertThat(metrics.snapshot()).containsEntry("validationRefused", 1L);
    }

    // ── the actual output ──

    @Test
    void aCompletedRunReportsCoverageConfidenceAndPerFieldDetail() {
        discovers(List.of(
                control("#first_name", "input", "text", "First Name", true),
                control("#last_name", "input", "text", "Last Name", true),
                control("#email", "input", "email", "Email", true)));

        ValidationReport report = harness(true, true)
                .validate(URL, userId, null, BrowserValidationHarness.DocumentAvailability.none());

        assertThat(report.status()).isEqualTo(ValidationReport.Status.COMPLETED);
        assertThat(report.platform()).isEqualTo(AtsPlatform.GREENHOUSE);
        assertThat(report.fields()).hasSize(3);
        assertThat(report.coverage().totalControls()).isEqualTo(3);
        assertThat(report.confidence().ready()).isTrue();
        assertThat(report.fields()).allSatisfy(f -> assertThat(f.resolvable()).isTrue());
    }

    @Test
    void aRequiredResumeWithNoDocumentAvailableIsReportedAsBlockingAndNotReady() {
        discovers(List.of(
                control("#email", "input", "email", "Email", true),
                control("#resume", "input", "file", "Resume", true)));

        ValidationReport report = harness(true, true)
                .validate(URL, userId, null, BrowserValidationHarness.DocumentAvailability.none());

        assertThat(report.coverage().missingRequiredValues()).isEqualTo(1);
        assertThat(report.confidence().ready()).isFalse();
        assertThat(report.confidence().band()).isEqualTo(AutomationConfidence.Band.LOW);
    }

    @Test
    void unresolvableFieldsCarryTheirReason() {
        discovers(List.of(control("#phone", "input", "tel", "Phone", false)));

        ValidationReport report = harness(true, true)
                .validate(URL, userId, null, BrowserValidationHarness.DocumentAvailability.none());

        ValidationReport.FieldEntry phone = report.fields().get(0);
        assertThat(phone.resolvable()).isFalse();
        // Phase C changed the reason text — phone now has a backing column this user has not filled
        // in, rather than no column at all. What matters is that a reason is always carried: an
        // unresolvable field with no explanation is a dead end for whoever has to act on it.
        assertThat(phone.unresolvedReason()).isNotBlank();
    }

    @Test
    void theReportNeverContainsCandidateValues() {
        discovers(List.of(control("#email", "input", "email", "Email", true)));

        String rendered = String.valueOf(harness(true, true)
                .validate(URL, userId, null, BrowserValidationHarness.DocumentAvailability.none()).snapshot());

        // Identity and classification, never the value that would be typed.
        assertThat(rendered).contains("EMAIL").doesNotContain("ada@example.com");
    }

    // ── page environment ──

    @Test
    void anEmptyPageWithIframesExplainsWhyDiscoveryFoundNothing() {
        when(browser.evaluate(FormDiscoveryScript.DISCOVER_FIELDS)).thenReturn(List.of());
        when(browser.evaluate(FormDiscoveryScript.DISCOVER_ENVIRONMENT))
                .thenReturn(Map.of("spaFramework", "React", "iframeCount", 2, "shadowRootCount", 0,
                        "captchaDetected", false, "cookieBannerDetected", true,
                        "failedRequests", 0, "title", "Apply"));

        ValidationReport report = harness(true, true)
                .validate(URL, userId, null, BrowserValidationHarness.DocumentAvailability.none());

        assertThat(report.notes()).anySatisfy(n -> assertThat(n).contains("iframe"));
        assertThat(report.environment().spaFramework()).isEqualTo("React");
    }

    @Test
    void aCaptchaIsReportedAndNeverSolved() {
        when(browser.currentPageHtml()).thenReturn("<html><body><div class='g-recaptcha'></div></body></html>");
        discovers(List.of());

        ValidationReport report = harness(true, true)
                .validate(URL, userId, null, BrowserValidationHarness.DocumentAvailability.none());

        assertThat(report.notes()).anySatisfy(n -> assertThat(n).containsIgnoringCase("captcha"));
        // Detection only — nothing was clicked or typed anywhere.
        verify(browser, never()).clickAt(anyString());
        verify(browser, never()).fillAt(anyString(), anyString());
    }

    @Test
    void thePageIsAllowedToSettleBeforeDiscoveryRuns() {
        // Without this the harness discovers an empty <div id="app"> on any SPA-rendered ATS.
        discovers(List.of(control("#email", "input", "email", "Email", false)));
        harness(true, true).validate(URL, userId, null, BrowserValidationHarness.DocumentAvailability.none());
        verify(browser).waitForStable(anyLong());
    }

    // ── metrics & compatibility ──

    @Test
    void theCompatibilityReportRecordsTheLatestPerPlatformAndSaysSo() {
        discovers(List.of(control("#email", "input", "email", "Email", false)));
        harness(true, true).validate(URL, userId, null, BrowserValidationHarness.DocumentAvailability.none());

        Map<String, Object> compatibility = metrics.compatibilityReport();
        assertThat(compatibility).containsKey("GREENHOUSE");
        @SuppressWarnings("unchecked")
        Map<String, Object> row = (Map<String, Object>) compatibility.get("GREENHOUSE");
        assertThat(row).containsKeys("automationConfidence", "band", "ready", "selectorsSupported");
        assertThat(String.valueOf(row.get("basis"))).contains("not an average");
    }

    @Test
    void lastValidationIsSurfacedForDiagnosticsIncludingFailures() {
        when(browser.evaluate(anyString())).thenThrow(new IllegalStateException("boom"));
        harness(true, true).validate(URL, userId, null, BrowserValidationHarness.DocumentAvailability.none());

        assertThat(metrics.lastReport()).isNotNull();
        assertThat(metrics.lastReport().status()).isEqualTo(ValidationReport.Status.FAILED);
        assertThat(metrics.snapshot().get("lastValidation")).isNotNull();
    }

    /**
     * Phase C added an ATS-profile source to the mapper. These pre-Phase-C tests deliberately pass a
     * DISABLED one: with the flag off the mapper must behave exactly as it did before the phase, so
     * every assertion below doubles as a backward-compatibility check.
     */
    private static ai.careerpilot.service.profile.ats.CandidateAtsProfileService disabledAtsProfiles() {
        return new ai.careerpilot.service.profile.ats.CandidateAtsProfileService(org.mockito.Mockito.mock(ai.careerpilot.repo.CandidateAtsProfileRepository.class), false);
    }
}
