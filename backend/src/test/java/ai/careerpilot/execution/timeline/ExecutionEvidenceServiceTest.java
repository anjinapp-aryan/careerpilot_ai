package ai.careerpilot.execution.timeline;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * P7 Action 7 — Execution Visibility. Proves the truth-model derivation rules directly: every
 * boolean/count either comes from a real recorded stage or is explicitly {@code null}, and the
 * coarse {@code state} matches the stage sequence actually reached. {@link ExecutionTimelineService}
 * is mocked — its own read/ownership contract is already covered elsewhere; what needs proving
 * here is the derivation logic over whatever it returns.
 */
class ExecutionEvidenceServiceTest {

    private ExecutionTimelineService timelineService;
    private ExecutionEvidenceService evidence;
    private final UUID userId = UUID.randomUUID();
    private final UUID executionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        timelineService = mock(ExecutionTimelineService.class);
        evidence = new ExecutionEvidenceService(timelineService);
    }

    private static Map<String, Object> stage(String name, String status) {
        return stage(name, status, null, null);
    }

    private static Map<String, Object> stage(String name, String status, String reason, String detailJson) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stage", name);
        m.put("status", status);
        m.put("displayName", name);
        m.put("reason", reason);
        m.put("detail", detailJson);
        m.put("startedAt", "2026-08-09T10:00:00Z");
        m.put("endedAt", "2026-08-09T10:00:01Z");
        return m;
    }

    private void stub(List<Map<String, Object>> stages, String outcome, String reason, boolean instrumentationEnabled) {
        Map<String, Object> full = new LinkedHashMap<>();
        full.put("stages", stages);
        full.put("executionStatus", "RUNNING");
        full.put("instrumentationEnabled", instrumentationEnabled);
        Map<String, Object> exit = new LinkedHashMap<>();
        exit.put("outcome", outcome);
        exit.put("reason", reason);
        exit.put("stoppedAtDisplayName", "Some Stage");
        exit.put("failureCategory", null);
        full.put("exit", exit);
        when(timelineService.timeline(eq(executionId), eq(userId))).thenReturn(Optional.of(full));
    }

    @Test
    void noExecutionYet_returnsNotStartedWithoutCallingTimelineService() {
        Map<String, Object> out = evidence.evidenceFor(userId, null);

        assertThat(out.get("hasExecution")).isEqualTo(false);
        assertThat(out.get("state")).isEqualTo("NOT_STARTED");
        assertThat(out.get("automationStarted")).isEqualTo(false);
        verifyNoInteractions(timelineService);
    }

    @Test
    void executionNotFoundOrNotOwned_returnsNotStarted_neverLeaksExistence() {
        when(timelineService.timeline(any(), any())).thenReturn(Optional.empty());

        Map<String, Object> out = evidence.evidenceFor(userId, executionId);

        assertThat(out.get("hasExecution")).isEqualTo(false);
        assertThat(out.get("state")).isEqualTo("NOT_STARTED");
    }

    @Test
    void captchaStop_reportsEmployerPageReachedTrueAndCaptchaOrLoginDetectedTrue_formDiscoveredFalse() {
        List<Map<String, Object>> stages = List.of(
                stage("NAVIGATION_STARTED", "COMPLETED"),
                stage("NAVIGATION_COMPLETED", "COMPLETED"),
                stage("PAGE_CLASSIFIED", "FAILED", "captcha or login wall detected — routed to human review", null));
        stub(stages, "FAILED", "captcha or login wall detected — routed to human review", true);

        Map<String, Object> out = evidence.evidenceFor(userId, executionId);

        assertThat(out.get("employerPageReached")).isEqualTo(true);
        assertThat(out.get("captchaOrLoginDetected")).isEqualTo(true);
        // Never even attempted discovery (stopped at PAGE_CLASSIFIED) — a proven false, not a guess.
        assertThat(out.get("formDiscovered")).isEqualTo(false);
        assertThat(out.get("automationStopped")).isEqualTo(true);
        assertThat(out.get("state")).isEqualTo("EMPLOYER_PAGE_REACHED");
    }

    @Test
    void noCaptchaOrLoginStop_reportsFalseNotNull() {
        List<Map<String, Object>> stages = List.of(
                stage("NAVIGATION_STARTED", "COMPLETED"),
                stage("NAVIGATION_COMPLETED", "COMPLETED"),
                stage("PAGE_CLASSIFIED", "COMPLETED"));
        stub(stages, "IN_FLIGHT", null, true);

        Map<String, Object> out = evidence.evidenceFor(userId, executionId);

        assertThat(out.get("captchaOrLoginDetected")).isEqualTo(false);
    }

    @Test
    void unsupportedControlStop_reportsQuestionResolutionBlockingGaps() {
        List<Map<String, Object>> stages = List.of(
                stage("NAVIGATION_STARTED", "COMPLETED"),
                stage("NAVIGATION_COMPLETED", "COMPLETED"),
                stage("PAGE_CLASSIFIED", "COMPLETED"),
                stage("FIELD_FILL_STARTED", "COMPLETED", null, "{\"connectorFieldsFilled\":3}"),
                stage("FORM_DISCOVERED", "COMPLETED", null, "{\"fieldsDiscovered\":18}"),
                stage("FIELD_FILL_COMPLETED", "FAILED", "required fields could not be filled from verified data: X", null));
        stub(stages, "FAILED", "required fields could not be filled from verified data: X", true);

        Map<String, Object> out = evidence.evidenceFor(userId, executionId);

        assertThat(out.get("formDiscovered")).isEqualTo(true);
        assertThat(out.get("fieldsDiscovered")).isEqualTo(18);
        assertThat(out.get("state")).isEqualTo("PARTIALLY_FILLED");
        assertThat(out.get("automationStopped")).isEqualTo(true);
    }

    @Test
    void fieldsFilled_sumsConnectorAndEngineCounts_onlyWhenRecorded() {
        List<Map<String, Object>> stages = List.of(
                stage("NAVIGATION_STARTED", "COMPLETED"),
                stage("NAVIGATION_COMPLETED", "COMPLETED"),
                stage("PAGE_CLASSIFIED", "COMPLETED"),
                stage("FIELD_FILL_STARTED", "COMPLETED", null, "{\"connectorFieldsFilled\":3}"),
                stage("FORM_DISCOVERED", "COMPLETED", null, "{\"fieldsDiscovered\":18}"),
                stage("QUESTIONS_RESOLVED", "COMPLETED", null, "{\"resolvedCount\":15,\"unresolvedCount\":3}"),
                stage("FIELD_FILL_COMPLETED", "COMPLETED", null, "{\"engineFieldsFilled\":12,\"engineFieldsSkipped\":3}"),
                stage("SUBMIT_CLICK_STARTED", "COMPLETED"),
                stage("SUBMIT_CLICK_COMPLETED", "COMPLETED"),
                stage("RESULT_PERSISTED", "COMPLETED"),
                stage("COMPLETED", "COMPLETED"));
        stub(stages, "COMPLETED", null, true);

        Map<String, Object> out = evidence.evidenceFor(userId, executionId);

        assertThat(out.get("fieldsFilled")).isEqualTo(15); // 3 connector + 12 engine
        assertThat(out.get("fieldsResolved")).isEqualTo(15);
        assertThat(out.get("fieldsUnresolved")).isEqualTo(3);
        assertThat(out.get("state")).isEqualTo("COMPLETED");
        assertThat(out.get("automationStopped")).isEqualTo(false);
    }

    @Test
    void noStagesAtAll_instrumentationOff_reportsNotStartedNotFalse() {
        stub(List.of(), "NO_STAGES_RECORDED", null, false);

        Map<String, Object> out = evidence.evidenceFor(userId, executionId);

        assertThat(out.get("automationStarted")).isEqualTo(false);
        assertThat(out.get("state")).isEqualTo("NOT_STARTED");
        assertThat(out.get("employerPageReached")).isNull();
        assertThat(out.get("instrumentationEnabled")).isEqualTo(false);
    }

    @Test
    void fieldsVerifiedCount_isNeverFabricated_noSuchKeyInOutput() {
        List<Map<String, Object>> stages = List.of(stage("NAVIGATION_STARTED", "COMPLETED"));
        stub(stages, "IN_FLIGHT", null, true);

        Map<String, Object> out = evidence.evidenceFor(userId, executionId);

        assertThat(out).doesNotContainKey("fieldsVerified");
    }
}
