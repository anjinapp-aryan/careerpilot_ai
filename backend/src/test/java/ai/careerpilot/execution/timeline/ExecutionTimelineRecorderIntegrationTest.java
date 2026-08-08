package ai.careerpilot.execution.timeline;

import ai.careerpilot.domain.ExecutionStageEvent;
import ai.careerpilot.repo.ExecutionStageEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P7 Action 4 — real-Postgres proof that one execution's stage sequence persists as multiple,
 * correctly-ordered, correctly-timed rows, and that a failure closes the stage it actually opened
 * rather than corrupting the sequence. {@link ExecutionTimelineRecorderTest} already proves the
 * recorder's mocked-repository call contract; this proves the same recorder against a real schema
 * (the {@code execution_stage_event} table from {@code V84__execution_stage_event.sql}).
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ExecutionStageMetrics.class)
@org.springframework.transaction.annotation.Transactional(propagation = Propagation.NOT_SUPPORTED)
class ExecutionTimelineRecorderIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @Autowired
    ExecutionStageEventRepository events;

    ExecutionTimelineRecorder recorder;
    ExecutionTimelineRecorder.RunContext run;

    @BeforeEach
    void setUp() {
        recorder = new ExecutionTimelineRecorder(events, new ExecutionStageMetrics(), true);
        run = new ExecutionTimelineRecorder.RunContext(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    }

    @Test
    void oneExecutionProducesMultipleCorrectlyOrderedAndTimedRows() throws InterruptedException {
        UUID discovery = recorder.started(run, ExecutionStage.FORM_DISCOVERY_STARTED);
        Thread.sleep(5); // real, measurable duration — not asserting an exact value, only that one exists
        recorder.completed(discovery, Map.of("fieldsDiscovered", 3));
        recorder.mark(run, ExecutionStage.FORM_DISCOVERED, Map.of("fieldsDiscovered", 3));
        recorder.mark(run, ExecutionStage.QUESTIONS_EXTRACTED, Map.of("questionCount", 3));

        UUID resolve = recorder.started(run, ExecutionStage.QUESTIONS_RESOLVED);
        recorder.completed(resolve, Map.of("resolvedCount", 2, "unresolvedCount", 1, "blockedCount", 0));

        UUID upload = recorder.started(run, ExecutionStage.DOCUMENT_UPLOAD_STARTED);
        recorder.completed(upload, Map.of("uploadsCompleted", 1));

        UUID verify = recorder.started(run, ExecutionStage.VERIFICATION_STARTED);
        recorder.completed(verify, Map.of("verdict", "VERIFIED"));
        recorder.mark(run, ExecutionStage.VERIFICATION_COMPLETED, Map.of("verdict", "VERIFIED"));

        recorder.mark(run, ExecutionStage.RESULT_PERSISTED, Map.of("finalStatus", "SUBMITTED"));

        List<ExecutionStageEvent> timeline = events.findByExecutionIdOrderBySequenceNoAsc(run.executionId());

        // 8 rows: FORM_DISCOVERY_STARTED and QUESTIONS_RESOLVED and DOCUMENT_UPLOAD_STARTED and
        // VERIFICATION_STARTED are each one started->completed row; FORM_DISCOVERED,
        // QUESTIONS_EXTRACTED, VERIFICATION_COMPLETED and RESULT_PERSISTED are each one mark() row.
        assertThat(timeline).hasSize(8);
        assertThat(timeline).extracting(ExecutionStageEvent::getStage).containsExactly(
                ExecutionStage.FORM_DISCOVERY_STARTED.name(),
                ExecutionStage.FORM_DISCOVERED.name(),
                ExecutionStage.QUESTIONS_EXTRACTED.name(),
                ExecutionStage.QUESTIONS_RESOLVED.name(),
                ExecutionStage.DOCUMENT_UPLOAD_STARTED.name(),
                ExecutionStage.VERIFICATION_STARTED.name(),
                ExecutionStage.VERIFICATION_COMPLETED.name(),
                ExecutionStage.RESULT_PERSISTED.name());
        assertThat(timeline).allSatisfy(row -> assertThat(row.getSequenceNo()).isNotNull());
        assertThat(timeline.get(0).getDurationMs()).isNotNull().isGreaterThanOrEqualTo(5L);

        ExecutionStageEvent discoveryRow = timeline.get(0);
        assertThat(discoveryRow.getStatus()).isEqualTo(ExecutionStageEvent.STATUS_COMPLETED);
        ExecutionStageEvent resultRow = timeline.get(timeline.size() - 1);
        assertThat(resultRow.getStage()).isEqualTo(ExecutionStage.RESULT_PERSISTED.name());
        assertThat(resultRow.getStatus()).isEqualTo(ExecutionStageEvent.STATUS_COMPLETED);
    }

    /**
     * A failure closes exactly the row it opened, as {@code FAILED}, and never fabricates a
     * completion for the same or a later stage.
     */
    @Test
    void aFailedStageNeverProducesAFabricatedCompletionMark() {
        UUID discovery = recorder.started(run, ExecutionStage.FORM_DISCOVERY_STARTED);
        recorder.failed(discovery, FailureCategory.QUESTION_PARSING, "no form fields discovered on the page");

        List<ExecutionStageEvent> timeline = events.findByExecutionIdOrderBySequenceNoAsc(run.executionId());

        assertThat(timeline).hasSize(1);
        assertThat(timeline.get(0).getStatus()).isEqualTo(ExecutionStageEvent.STATUS_FAILED);
        assertThat(timeline.get(0).getFailureCategory()).isEqualTo(FailureCategory.QUESTION_PARSING.name());
    }

    /**
     * P7 Action 4 Step 3 — process-death semantics. A stage left open (no completed/failed call —
     * simulating the JVM dying mid-operation) must persist as a genuinely ambiguous open row, never
     * silently promoted to a terminal status by anything reading it later.
     */
    @Test
    void anOpenStageWithNoTerminalCallStaysGenuinelyAmbiguous() {
        recorder.started(run, ExecutionStage.SUBMIT_CLICK_STARTED);
        // Simulated process death: no completed()/failed() call ever arrives for this row.

        List<ExecutionStageEvent> timeline = events.findByExecutionIdOrderBySequenceNoAsc(run.executionId());

        assertThat(timeline).hasSize(1);
        assertThat(timeline.get(0).getStatus()).isEqualTo(ExecutionStageEvent.STATUS_STARTED);
        assertThat(timeline.get(0).getEndedAt()).isNull();
        assertThat(timeline.get(0).getDurationMs()).isNull();
    }

    /** Recorder failure isolation: an unusable RunContext degrades to no-op, never an exception. */
    @Test
    void nullRunContextIsANoOpNotAnException() {
        UUID id = recorder.started(null, ExecutionStage.FORM_DISCOVERY_STARTED);
        assertThat(id).isNull();
        // completed()/failed() on a null id must also be safe no-ops.
        recorder.completed(null, Map.of("x", 1));
        recorder.failed(null, FailureCategory.UNKNOWN, "irrelevant");
    }
}
