package ai.careerpilot.execution.browser.form;

import ai.careerpilot.execution.browser.PlaywrightAutomationProvider;
import ai.careerpilot.execution.timeline.ExecutionStage;
import ai.careerpilot.execution.timeline.ExecutionTimelineRecorder;
import ai.careerpilot.execution.timeline.FailureCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * P7 Action 4 — the first dedicated test file for {@link BrowserFormAutomationEngine}, covering
 * only the new timeline instrumentation seams this action adds. Fill-decision correctness
 * (classification, resolution, blocking gaps) is already covered by {@code FormFillPlannerTest} /
 * {@code FieldClassifierTest} and is untouched here.
 *
 * <p>The recorder is mocked rather than real+Postgres for these — {@code
 * ExecutionTimelineRecorderTest} already proves the recorder's own persistence/failure-isolation
 * contract; what needs proving here is that the engine calls it at the right seams with the right
 * stage/status/category, which a mock verifies directly and readably.
 */
class BrowserFormAutomationEngineTimelineTest {

    private PlaywrightAutomationProvider browser;
    private FormFillPlanner planner;
    private ValidationErrorDetector validationDetector;
    private MultiStepFormNavigator navigator;
    private FormAutomationMetrics metrics;
    private ExecutionTimelineRecorder timeline;
    private BrowserFormAutomationEngine engine;

    private final ExecutionTimelineRecorder.RunContext run =
            new ExecutionTimelineRecorder.RunContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

    @BeforeEach
    void setUp() {
        browser = mock(PlaywrightAutomationProvider.class);
        planner = mock(FormFillPlanner.class);
        validationDetector = mock(ValidationErrorDetector.class);
        navigator = mock(MultiStepFormNavigator.class);
        metrics = mock(FormAutomationMetrics.class);
        timeline = mock(ExecutionTimelineRecorder.class);
        engine = new BrowserFormAutomationEngine(browser, planner, validationDetector, navigator,
                metrics, timeline, true, true);

        when(validationDetector.detect(any())).thenReturn(List.of());
        when(browser.evaluate(FormDiscoveryScript.DISCOVER_VALIDATION_STATE)).thenReturn(Map.of());
    }

    private DiscoveredField textField(String label, boolean required) {
        return new DiscoveredField("#email", FieldControlType.EMAIL, "email", "email",
                label, "", "", "", "", required, false, false, false, -1, List.of());
    }

    /** Stage id stubs so started()->completed()/failed() calls can be chained by the engine under test. */
    private void stubStageIds() {
        when(timeline.started(any(), any())).thenReturn(UUID.randomUUID());
        when(timeline.started(any(), any(), any())).thenReturn(UUID.randomUUID());
    }

    @Test
    void discoveryFailureMarksFormDiscoveryStartedFailedAndNeverMarksFormDiscovered() {
        stubStageIds();
        when(browser.evaluate(FormDiscoveryScript.DISCOVER_FIELDS)).thenReturn(Map.of("fields", List.of()));

        BrowserFormAutomationEngine.FillOutcome outcome =
                engine.fillForm(UUID.randomUUID(), UUID.randomUUID(), BrowserFormAutomationEngine.DocumentPaths.none(), run);

        assertThat(outcome.success()).isFalse();
        verify(timeline).started(eq(run), eq(ExecutionStage.FORM_DISCOVERY_STARTED));
        verify(timeline).failed(any(), eq(FailureCategory.QUESTION_PARSING), anyString());
        // The most important assertion: no fabricated completion. A discovery failure must not also
        // claim FORM_DISCOVERED or QUESTIONS_EXTRACTED happened.
        verify(timeline, never()).completed(any());
        verify(timeline, never()).completed(any(), any());
        verify(timeline, never()).mark(any(), eq(ExecutionStage.FORM_DISCOVERED), any());
        verify(timeline, never()).mark(any(), eq(ExecutionStage.QUESTIONS_EXTRACTED), any());
    }

    @Test
    void successfulDiscoveryMarksFormDiscoveredAndQuestionsExtractedWithRealCounts() {
        stubStageIds();
        when(browser.evaluate(FormDiscoveryScript.DISCOVER_FIELDS)).thenReturn(Map.of(
                "fields", List.of(fieldMap("email", true), fieldMap("phone", false))));
        when(planner.plan(any(), any(), any(), any())).thenReturn(FormFillPlan.empty());

        engine.fillForm(UUID.randomUUID(), UUID.randomUUID(),
                BrowserFormAutomationEngine.DocumentPaths.none(), run);

        verify(timeline).completed(any(), argThat(m ->
                m.containsKey("fieldsDiscovered") && ((Number) m.get("fieldsDiscovered")).intValue() == 2));
        verify(timeline).mark(eq(run), eq(ExecutionStage.FORM_DISCOVERED), any());
        verify(timeline).mark(eq(run), eq(ExecutionStage.QUESTIONS_EXTRACTED), argThat(m ->
                m.containsKey("questionCount") && ((Number) m.get("questionCount")).intValue() == 2
                        && ((Number) m.get("requiredQuestionCount")).longValue() == 1L));
    }

    private static Map<String, Object> fieldMap(String name, boolean required) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("selector", "#" + name);
        m.put("tag", "input");
        m.put("type", "text");
        m.put("name", name);
        m.put("id", name);
        m.put("label", name);
        m.put("required", required);
        return m;
    }

    @Test
    void questionsResolvedReportsRealResolvedUnresolvedAndBlockedCounts() {
        stubStageIds();
        when(browser.evaluate(FormDiscoveryScript.DISCOVER_FIELDS)).thenReturn(Map.of(
                "fields", List.of(fieldMap("email", true))));
        DiscoveredField field = textField("Email", true);
        FormFillPlan.PlannedFill fill = new FormFillPlan.PlannedFill(field, CanonicalField.EMAIL, "a@b.com", "profile");
        FormFillPlan.UnresolvedField unresolved = new FormFillPlan.UnresolvedField(
                textField("Phone", false), CanonicalField.UNKNOWN, "no data", false);
        when(planner.plan(any(), any(), any(), any())).thenReturn(new FormFillPlan(List.of(fill), List.of(unresolved)));

        engine.fillForm(UUID.randomUUID(), UUID.randomUUID(),
                BrowserFormAutomationEngine.DocumentPaths.none(), run);

        verify(timeline).started(eq(run), eq(ExecutionStage.QUESTIONS_RESOLVED));
        verify(timeline).completed(any(), argThat(m ->
                m.containsKey("resolvedCount")
                        && ((Number) m.get("resolvedCount")).intValue() == 1
                        && ((Number) m.get("unresolvedCount")).intValue() == 1
                        && ((Number) m.get("blockedCount")).intValue() == 0));
    }

    @Test
    void noPlannedUploadsNeverOpensDocumentUploadStage() {
        stubStageIds();
        when(browser.evaluate(FormDiscoveryScript.DISCOVER_FIELDS)).thenReturn(Map.of(
                "fields", List.of(fieldMap("email", true))));
        DiscoveredField field = textField("Email", true);
        FormFillPlan.PlannedFill fill = new FormFillPlan.PlannedFill(field, CanonicalField.EMAIL, "a@b.com", "profile");
        when(planner.plan(any(), any(), any(), any())).thenReturn(new FormFillPlan(List.of(fill), List.of()));

        engine.fillForm(UUID.randomUUID(), UUID.randomUUID(),
                BrowserFormAutomationEngine.DocumentPaths.none(), run);

        verify(timeline, never()).started(any(), eq(ExecutionStage.DOCUMENT_UPLOAD_STARTED));
        verify(timeline, never()).started(any(), eq(ExecutionStage.DOCUMENT_UPLOAD_STARTED), any());
    }

    @Test
    void successfulUploadMarksDocumentUploadCompletedNeverFailed() {
        stubStageIds();
        when(browser.evaluate(FormDiscoveryScript.DISCOVER_FIELDS)).thenReturn(Map.of(
                "fields", List.of(fieldMap("resume", true))));
        DiscoveredField field = new DiscoveredField("#resume", FieldControlType.FILE, "resume", "resume",
                "Resume", "", "", "", "", true, false, false, false, -1, List.of());
        FormFillPlan.PlannedFill fill = new FormFillPlan.PlannedFill(
                field, CanonicalField.RESUME_UPLOAD, FormFillPlanner.DocumentSlot.RESUME.token(), "package");
        when(planner.plan(any(), any(), any(), any())).thenReturn(new FormFillPlan(List.of(fill), List.of()));
        when(browser.hasUploadedFile("#resume")).thenReturn(true);

        Path fakeResume = Path.of("resume.pdf");
        engine.fillForm(UUID.randomUUID(), UUID.randomUUID(),
                new BrowserFormAutomationEngine.DocumentPaths(fakeResume, "resume.pdf", null, null), run);

        verify(timeline).started(eq(run), eq(ExecutionStage.DOCUMENT_UPLOAD_STARTED), any());
        verify(timeline).completed(any(), argThat(m ->
                m.containsKey("uploadsCompleted") && ((Number) m.get("uploadsCompleted")).longValue() == 1L));
        verify(timeline).mark(eq(run), eq(ExecutionStage.DOCUMENT_UPLOAD_COMPLETED), any());
        verify(timeline, never()).failed(any(), eq(FailureCategory.UPLOAD), anyString());
    }

    @Test
    void failedUploadMarksDocumentUploadFailedNeverCompleted() {
        stubStageIds();
        when(browser.evaluate(FormDiscoveryScript.DISCOVER_FIELDS)).thenReturn(Map.of(
                "fields", List.of(fieldMap("resume", true))));
        DiscoveredField field = new DiscoveredField("#resume", FieldControlType.FILE, "resume", "resume",
                "Resume", "", "", "", "", true, false, false, false, -1, List.of());
        FormFillPlan.PlannedFill fill = new FormFillPlan.PlannedFill(
                field, CanonicalField.RESUME_UPLOAD, FormFillPlanner.DocumentSlot.RESUME.token(), "package");
        when(planner.plan(any(), any(), any(), any())).thenReturn(new FormFillPlan(List.of(fill), List.of()));
        // Upload "succeeds" at the Playwright call level but the read-back verification fails —
        // the exact "detached input silently no-ops" case this platform refuses to certify.
        when(browser.hasUploadedFile("#resume")).thenReturn(false);

        Path fakeResume = Path.of("resume.pdf");
        engine.fillForm(UUID.randomUUID(), UUID.randomUUID(),
                new BrowserFormAutomationEngine.DocumentPaths(fakeResume, "resume.pdf", null, null), run);

        verify(timeline).failed(any(), eq(FailureCategory.UPLOAD), anyString());
        verify(timeline, never()).completed(any(), argThat(m -> m.containsKey("uploadsCompleted")));
        // No fabricated completion: a failed upload must never also mark DOCUMENT_UPLOAD_COMPLETED.
        verify(timeline, never()).mark(any(), eq(ExecutionStage.DOCUMENT_UPLOAD_COMPLETED), any());
    }

    /**
     * The pre-existing 3-arg overload delegates to the 4-arg one with {@code run = null} — against a
     * mock, that still registers as a method call (the mock does not know the real recorder's
     * null-run contract), so what actually needs proving is the real recorder's behaviour: nothing
     * reaches the repository, i.e. no row is ever written. That is the guarantee that matters —
     * MultiStepExecutionOrchestrator and attemptFill depend on this overload being a true no-op.
     */
    @Test
    void legacyThreeArgOverloadWritesNoTimelineRowWithTheRealRecorder() {
        when(browser.evaluate(FormDiscoveryScript.DISCOVER_FIELDS)).thenReturn(Map.of(
                "fields", List.of(fieldMap("email", true))));
        when(planner.plan(any(), any(), any(), any())).thenReturn(FormFillPlan.empty());

        ai.careerpilot.repo.ExecutionStageEventRepository events =
                mock(ai.careerpilot.repo.ExecutionStageEventRepository.class);
        ExecutionTimelineRecorder realRecorder = new ExecutionTimelineRecorder(
                events, new ai.careerpilot.execution.timeline.ExecutionStageMetrics(), true);
        BrowserFormAutomationEngine engineWithRealRecorder = new BrowserFormAutomationEngine(
                browser, planner, validationDetector, navigator, metrics, realRecorder, true, true);

        engineWithRealRecorder.fillForm(UUID.randomUUID(), UUID.randomUUID(),
                BrowserFormAutomationEngine.DocumentPaths.none());

        verifyNoInteractions(events);
    }
}
