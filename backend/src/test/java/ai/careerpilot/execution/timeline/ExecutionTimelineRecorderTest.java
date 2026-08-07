package ai.careerpilot.execution.timeline;

import ai.careerpilot.domain.ExecutionStageEvent;
import ai.careerpilot.repo.ExecutionStageEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P5 — the recorder's one hard contract: <b>observability can never break the thing it observes</b>.
 *
 * <p>This is instrumentation threaded through the only code path in the platform that clicks submit
 * on a live employer form. A timeline write that throws there must not be able to change whether an
 * application is sent, retried or recorded — so every test below is really the same test asked of a
 * different failure.
 */
class ExecutionTimelineRecorderTest {

    private ExecutionStageEventRepository events;
    private ExecutionStageMetrics metrics;

    private final UUID executionId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        events = mock(ExecutionStageEventRepository.class);
        metrics = new ExecutionStageMetrics();
        when(events.save(any(ExecutionStageEvent.class))).thenAnswer(inv -> {
            ExecutionStageEvent row = inv.getArgument(0);
            if (row.getId() == null) row.setId(UUID.randomUUID());
            return row;
        });
    }

    private ExecutionTimelineRecorder recorder(boolean enabled) {
        return new ExecutionTimelineRecorder(events, metrics, enabled);
    }

    private ExecutionTimelineRecorder.RunContext run() {
        return new ExecutionTimelineRecorder.RunContext(executionId, userId, jobId);
    }

    // ── The contract ──────────────────────────────────────────────────────────────────────────

    @Test
    void aThrowingRepositoryNeverPropagates() {
        when(events.save(any(ExecutionStageEvent.class))).thenThrow(new IllegalStateException("db down"));
        when(events.maxSequenceNo(any())).thenThrow(new IllegalStateException("db down"));
        ExecutionTimelineRecorder r = recorder(true);

        UUID id = r.started(run(), ExecutionStage.NAVIGATION_STARTED);

        assertThat(id).as("a failed open returns null rather than throwing").isNull();
        // And every close path tolerates that null without complaint.
        r.completed(id);
        r.failed(id, FailureCategory.BROWSER, "boom");
        r.skipped(id, "n/a");
        r.mark(run(), ExecutionStage.COMPLETED, null);
    }

    @Test
    void disabledRecorderTouchesNoRepository() {
        ExecutionTimelineRecorder r = recorder(false);

        assertThat(r.started(run(), ExecutionStage.NAVIGATION_STARTED)).isNull();
        r.mark(run(), ExecutionStage.APPROVAL_GRANTED, Map.of("k", "v"));
        r.completed(UUID.randomUUID());

        verify(events, never()).save(any());
        verify(events, never()).findById(any());
        assertThat(r.isEnabled()).isFalse();
    }

    @Test
    void anUnusableRunContextIsIgnoredRatherThanWritingAnOrphanRow() {
        ExecutionTimelineRecorder r = recorder(true);

        assertThat(r.started(null, ExecutionStage.NAVIGATION_STARTED)).isNull();
        assertThat(r.started(new ExecutionTimelineRecorder.RunContext(null, userId, jobId),
                ExecutionStage.NAVIGATION_STARTED)).isNull();
        assertThat(r.started(new ExecutionTimelineRecorder.RunContext(executionId, null, jobId),
                ExecutionStage.NAVIGATION_STARTED)).isNull();
        assertThat(r.started(run(), null)).isNull();

        verify(events, never()).save(any());
    }

    @Test
    void unserialisableDetailDegradesToNoDetailRatherThanFailingTheStage() {
        ExecutionTimelineRecorder r = recorder(true);
        Map<String, Object> cyclic = new HashMap<>();
        cyclic.put("self", cyclic);   // Jackson cannot serialise this

        UUID id = r.started(run(), ExecutionStage.FORM_DISCOVERED, cyclic);

        assertThat(id).as("the stage is still recorded — only its detail is dropped").isNotNull();
    }

    // ── Sequencing and closure semantics ──────────────────────────────────────────────────────

    @Test
    void stagesAreSequencedFromTheExistingMaximum() {
        when(events.maxSequenceNo(executionId)).thenReturn(7);
        ExecutionTimelineRecorder r = recorder(true);

        r.started(run(), ExecutionStage.NAVIGATION_STARTED);

        org.mockito.ArgumentCaptor<ExecutionStageEvent> captor =
                org.mockito.ArgumentCaptor.forClass(ExecutionStageEvent.class);
        verify(events).save(captor.capture());
        assertThat(captor.getValue().getSequenceNo()).isEqualTo(8);
        assertThat(captor.getValue().getStatus()).isEqualTo(ExecutionStageEvent.STATUS_STARTED);
        assertThat(captor.getValue().getEndedAt()).isNull();
    }

    @Test
    void theFirstStageOfAnExecutionStartsAtOne() {
        when(events.maxSequenceNo(executionId)).thenReturn(null);
        recorder(true).started(run(), ExecutionStage.CREATED);

        org.mockito.ArgumentCaptor<ExecutionStageEvent> captor =
                org.mockito.ArgumentCaptor.forClass(ExecutionStageEvent.class);
        verify(events).save(captor.capture());
        assertThat(captor.getValue().getSequenceNo()).isEqualTo(1);
    }

    @Test
    void closingAStageRecordsItsDurationAndCategory() {
        ExecutionStageEvent open = openRow();
        when(events.findById(open.getId())).thenReturn(Optional.of(open));

        recorder(true).failed(open.getId(), FailureCategory.QUESTION_RESOLUTION, "no verified salary answer");

        assertThat(open.getStatus()).isEqualTo(ExecutionStageEvent.STATUS_FAILED);
        assertThat(open.getEndedAt()).isNotNull();
        assertThat(open.getDurationMs()).isNotNull().isGreaterThanOrEqualTo(0L);
        assertThat(open.getFailureCategory()).isEqualTo(FailureCategory.QUESTION_RESOLUTION.name());
        assertThat(open.getReason()).contains("no verified salary answer");
    }

    /**
     * The append-only guarantee. The first outcome recorded for a stage is the true one; a second
     * close would silently rewrite history, and a timeline that can be rewritten is not evidence.
     */
    @Test
    void aClosedStageIsNeverRewritten() {
        ExecutionStageEvent open = openRow();
        when(events.findById(open.getId())).thenReturn(Optional.of(open));
        ExecutionTimelineRecorder r = recorder(true);

        r.failed(open.getId(), FailureCategory.BROWSER, "first and true outcome");
        r.completed(open.getId(), Map.of("late", "write"));

        assertThat(open.getStatus()).isEqualTo(ExecutionStageEvent.STATUS_FAILED);
        assertThat(open.getReason()).isEqualTo("first and true outcome");
    }

    @Test
    void markOpensAndClosesInOneStepSoNoStageIsLeftLookingInFlight() {
        ExecutionTimelineRecorder r = recorder(true);
        when(events.findById(any())).thenAnswer(inv -> {
            UUID id = inv.getArgument(0);
            ExecutionStageEvent row = openRow();
            row.setId(id);
            return Optional.of(row);
        });

        r.mark(run(), ExecutionStage.APPROVAL_GRANTED, null);

        // Once to open, once to close.
        verify(events, org.mockito.Mockito.times(2)).save(any(ExecutionStageEvent.class));
    }

    @Test
    void closingFeedsTheStageMetrics() {
        ExecutionStageEvent open = openRow();
        when(events.findById(open.getId())).thenReturn(Optional.of(open));

        recorder(true).failed(open.getId(), FailureCategory.UPLOAD, "resume upload could not be verified");

        Map<String, Object> snapshot = metrics.snapshot();
        assertThat(snapshot).containsEntry("topFailureStage", ExecutionStage.DOCUMENT_UPLOAD_STARTED.name());
        assertThat(snapshot).containsEntry("topFailureCategory", FailureCategory.UPLOAD.name());
    }

    @Test
    void aMissingRowIsANoOpNotAnError() {
        when(events.findById(any())).thenReturn(Optional.empty());
        recorder(true).completed(UUID.randomUUID());   // must not throw
    }

    private ExecutionStageEvent openRow() {
        return ExecutionStageEvent.builder()
                .id(UUID.randomUUID())
                .executionId(executionId).userId(userId).jobId(jobId)
                .sequenceNo(1)
                .stage(ExecutionStage.DOCUMENT_UPLOAD_STARTED.name())
                .status(ExecutionStageEvent.STATUS_STARTED)
                .startedAt(java.time.Instant.now().minusMillis(120))
                .build();
    }
}
