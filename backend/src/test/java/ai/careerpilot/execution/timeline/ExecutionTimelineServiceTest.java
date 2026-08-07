package ai.careerpilot.execution.timeline;

import ai.careerpilot.domain.ApplicationExecution;
import ai.careerpilot.domain.ExecutionStageEvent;
import ai.careerpilot.execution.browser.BrowserSessionManager;
import ai.careerpilot.execution.browser.pool.BrowserLeasePool;
import ai.careerpilot.repo.ApplicationExecutionRepository;
import ai.careerpilot.repo.ExecutionStageEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P5 — the read side answers one question: <b>exactly where did this application stop, and why?</b>
 * These tests are that question asked of each shape the data can take.
 */
class ExecutionTimelineServiceTest {

    private ExecutionStageEventRepository events;
    private ApplicationExecutionRepository executions;
    private BrowserSessionManager sessionManager;
    private BrowserLeasePool leasePool;
    private ExecutionTimelineService service;

    private final UUID executionId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        events = mock(ExecutionStageEventRepository.class);
        executions = mock(ApplicationExecutionRepository.class);
        sessionManager = mock(BrowserSessionManager.class);
        leasePool = mock(BrowserLeasePool.class);
        ExecutionTimelineRecorder recorder = new ExecutionTimelineRecorder(
                events, new ExecutionStageMetrics(), true);
        service = new ExecutionTimelineService(events, executions, new ExecutionStageMetrics(),
                sessionManager, leasePool, recorder);

        when(executions.findByIdAndUserId(executionId, userId)).thenReturn(Optional.of(execution()));
        when(events.findByExecutionIdOrderBySequenceNoAsc(executionId)).thenReturn(List.of());
    }

    private ApplicationExecution execution() {
        return ApplicationExecution.builder()
                .id(executionId).userId(userId).jobId(jobId)
                .executionStatus(ApplicationExecution.STATUS_FAILED)
                .failureReason("something went wrong")
                .build();
    }

    private ExecutionStageEvent stage(int seq, ExecutionStage s, String status,
                                      long durationMs, FailureCategory category, String reason) {
        Instant start = Instant.now().minusSeconds(60 - seq);
        return ExecutionStageEvent.builder()
                .id(UUID.randomUUID()).executionId(executionId).userId(userId).jobId(jobId)
                .sequenceNo(seq).stage(s.name()).status(status)
                .startedAt(start)
                .endedAt(ExecutionStageEvent.STATUS_STARTED.equals(status) ? null : start.plusMillis(durationMs))
                .durationMs(ExecutionStageEvent.STATUS_STARTED.equals(status) ? null : durationMs)
                .failureCategory(category == null ? null : category.name())
                .reason(reason)
                .build();
    }

    // ── Ownership ─────────────────────────────────────────────────────────────────────────────

    @Test
    void anotherUsersExecutionIsIndistinguishableFromOneThatDoesNotExist() {
        when(executions.findByIdAndUserId(any(), any())).thenReturn(Optional.empty());
        assertThat(service.timeline(executionId, UUID.randomUUID())).isEmpty();
    }

    // ── The headline answer ───────────────────────────────────────────────────────────────────

    @Test
    void reportsTheStageThatFailedAndWhy() {
        when(events.findByExecutionIdOrderBySequenceNoAsc(executionId)).thenReturn(List.of(
                stage(1, ExecutionStage.NAVIGATION_STARTED, ExecutionStageEvent.STATUS_COMPLETED, 3287, null, null),
                stage(2, ExecutionStage.FORM_DISCOVERED, ExecutionStageEvent.STATUS_COMPLETED, 15, null, null),
                stage(3, ExecutionStage.FIELD_FILL_COMPLETED, ExecutionStageEvent.STATUS_FAILED, 2418,
                        FailureCategory.QUESTION_RESOLUTION, "required fields could not be filled: salary expectation")));

        @SuppressWarnings("unchecked")
        Map<String, Object> exit = (Map<String, Object>) service.timeline(executionId, userId).orElseThrow().get("exit");

        assertThat(exit).containsEntry("stoppedAt", ExecutionStage.FIELD_FILL_COMPLETED.name());
        assertThat(exit).containsEntry("stoppedAtDisplayName", "Field Fill Completed");
        assertThat(exit).containsEntry("failureCategory", FailureCategory.QUESTION_RESOLUTION.name());
        assertThat(String.valueOf(exit.get("reason"))).contains("salary expectation");
        // The recovery decision is surfaced from the execution row, never recomputed, so the
        // timeline and the recovery centre cannot disagree.
        assertThat(exit).containsEntry("recoveryAction", ApplicationExecution.STATUS_FAILED);
    }

    /**
     * A later bookkeeping stage closing cleanly must not hide the failure that actually stopped the
     * run — otherwise the timeline would report the last thing that happened rather than the thing
     * that went wrong.
     */
    @Test
    void aFailedStageOutranksALaterCleanlyClosedStage() {
        when(events.findByExecutionIdOrderBySequenceNoAsc(executionId)).thenReturn(List.of(
                stage(1, ExecutionStage.SUBMIT_CLICK_STARTED, ExecutionStageEvent.STATUS_FAILED, 900,
                        FailureCategory.SUBMIT, "frame detached"),
                stage(2, ExecutionStage.RESULT_PERSISTED, ExecutionStageEvent.STATUS_COMPLETED, 12, null, null)));

        @SuppressWarnings("unchecked")
        Map<String, Object> exit = (Map<String, Object>) service.timeline(executionId, userId).orElseThrow().get("exit");

        assertThat(exit).containsEntry("stoppedAt", ExecutionStage.SUBMIT_CLICK_STARTED.name());
        assertThat(exit).containsEntry("failureCategory", FailureCategory.SUBMIT.name());
    }

    /**
     * A run killed mid-stage and a run still working look identical in the data. Reporting either
     * as a failure would be a claim the data does not support.
     */
    @Test
    void anOpenFinalStageIsReportedAsInFlightNotAsAFailure() {
        when(events.findByExecutionIdOrderBySequenceNoAsc(executionId)).thenReturn(List.of(
                stage(1, ExecutionStage.NAVIGATION_STARTED, ExecutionStageEvent.STATUS_COMPLETED, 100, null, null),
                stage(2, ExecutionStage.DOCUMENT_UPLOAD_STARTED, ExecutionStageEvent.STATUS_STARTED, 0, null, null)));

        @SuppressWarnings("unchecked")
        Map<String, Object> exit = (Map<String, Object>) service.timeline(executionId, userId).orElseThrow().get("exit");

        assertThat(exit).containsEntry("outcome", "IN_FLIGHT");
        assertThat(exit).containsEntry("stoppedAt", ExecutionStage.DOCUMENT_UPLOAD_STARTED.name());
    }

    // ── Durations ─────────────────────────────────────────────────────────────────────────────

    @Test
    void perStageDurationsAreReportedSoTheSlowComponentIsObvious() {
        when(events.findByExecutionIdOrderBySequenceNoAsc(executionId)).thenReturn(List.of(
                stage(1, ExecutionStage.NAVIGATION_STARTED, ExecutionStageEvent.STATUS_COMPLETED, 3287, null, null),
                stage(2, ExecutionStage.QUESTIONS_RESOLVED, ExecutionStageEvent.STATUS_COMPLETED, 2418, null, null),
                stage(3, ExecutionStage.DOCUMENT_UPLOAD_COMPLETED, ExecutionStageEvent.STATUS_COMPLETED, 642, null, null)));

        @SuppressWarnings("unchecked")
        Map<String, Long> durations = (Map<String, Long>)
                service.timeline(executionId, userId).orElseThrow().get("stageDurations");

        assertThat(durations).containsEntry(ExecutionStage.NAVIGATION_STARTED.name(), 3287L);
        assertThat(durations).containsEntry(ExecutionStage.QUESTIONS_RESOLVED.name(), 2418L);
        assertThat(durations).containsEntry(ExecutionStage.DOCUMENT_UPLOAD_COMPLETED.name(), 642L);
    }

    // ── Honesty about absence ─────────────────────────────────────────────────────────────────

    /**
     * "Instrumentation is off" and "this run recorded nothing" are different facts, and only one of
     * them means something is wrong. They must not render identically.
     */
    @Test
    void anExecutionWithNoStagesSaysWhichKindOfNothingItIs() {
        Map<String, Object> out = service.timeline(executionId, userId).orElseThrow();

        assertThat(out).containsEntry("instrumentationEnabled", true);
        assertThat(String.valueOf(out.get("note"))).contains("predates instrumentation");
        @SuppressWarnings("unchecked")
        Map<String, Object> exit = (Map<String, Object>) out.get("exit");
        assertThat(exit).containsEntry("outcome", "NO_STAGES_RECORDED");
    }

    @Test
    void aDisabledRecorderSaysSoRatherThanLookingLikeAnEmptyRun() {
        ExecutionTimelineService off = new ExecutionTimelineService(events, executions,
                new ExecutionStageMetrics(), sessionManager, leasePool,
                new ExecutionTimelineRecorder(events, new ExecutionStageMetrics(), false));

        Map<String, Object> out = off.timeline(executionId, userId).orElseThrow();

        assertThat(out).containsEntry("instrumentationEnabled", false);
        assertThat(String.valueOf(out.get("note"))).contains("disabled");
    }

    @Test
    void answerStatisticsAreAbsentRatherThanZeroFilledWhenResolutionNeverRan() {
        when(events.findByExecutionIdOrderBySequenceNoAsc(executionId)).thenReturn(List.of(
                stage(1, ExecutionStage.NAVIGATION_STARTED, ExecutionStageEvent.STATUS_COMPLETED, 100, null, null)));

        // "No questions were resolved" and "we never got that far" are different, and only one of
        // them is a problem — so this is null, not a zeroed summary.
        assertThat(service.timeline(executionId, userId).orElseThrow().get("answers")).isNull();
    }

    // ── Resilience ────────────────────────────────────────────────────────────────────────────

    @Test
    void aFailingStageReadStillReturnsAnAnswer() {
        when(events.findByExecutionIdOrderBySequenceNoAsc(executionId))
                .thenThrow(new IllegalStateException("db down"));

        Map<String, Object> out = service.timeline(executionId, userId).orElseThrow();

        // Whoever is reading this is usually mid-incident; a 500 is the worst possible response.
        assertThat(out).containsKey("exit");
        assertThat(out).containsEntry("executionStatus", ApplicationExecution.STATUS_FAILED);
    }

    @Test
    void aThrowingBrowserSnapshotDegradesToNullNotAFailedResponse() {
        when(sessionManager.isLaunched()).thenThrow(new IllegalStateException("browser gone"));

        Map<String, Object> out = service.timeline(executionId, userId).orElseThrow();

        assertThat(out).containsKey("exit");
        assertThat(out.get("browser")).isNull();
    }

    @Test
    void theBrowserSnapshotSaysItDescribesNowNotTheExecution() {
        when(sessionManager.isLaunched()).thenReturn(true);
        when(sessionManager.launchedAt()).thenReturn(Instant.now().minusSeconds(30));
        when(sessionManager.contextsSinceLaunch()).thenReturn(4L);
        when(leasePool.maxLeases()).thenReturn(1);

        @SuppressWarnings("unchecked")
        Map<String, Object> browser = (Map<String, Object>)
                service.timeline(executionId, userId).orElseThrow().get("browser");

        assertThat(browser).containsEntry("contextsServed", 4L);
        // The browser may have been recycled since; claiming otherwise would be a fabrication.
        assertThat(String.valueOf(browser.get("note"))).contains("recycled");
    }
}
