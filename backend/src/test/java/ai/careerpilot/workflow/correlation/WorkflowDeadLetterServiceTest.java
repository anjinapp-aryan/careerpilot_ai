package ai.careerpilot.workflow.correlation;

import ai.careerpilot.domain.WorkflowDeadLetter;
import ai.careerpilot.repo.WorkflowDeadLetterRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Phase 3A.0 — the dead-letter sink is the workflow's last line of defence: it captures a failed stage
 * and MUST NOT throw even if its own persistence fails. Long payloads/exceptions are truncated so an
 * oversized event can't break the insert.
 */
class WorkflowDeadLetterServiceTest {

    private final WorkflowDeadLetterRepository repo = mock(WorkflowDeadLetterRepository.class);
    private final WorkflowMetrics metrics = new WorkflowMetrics();
    private final WorkflowDeadLetterService service = new WorkflowDeadLetterService(repo, metrics);

    @Test
    void recordPersistsRow() {
        UUID cid = UUID.randomUUID();
        service.record(cid, "application-tracking", "track", "payload", new RuntimeException("boom"));
        ArgumentCaptor<WorkflowDeadLetter> captor = ArgumentCaptor.forClass(WorkflowDeadLetter.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getCorrelationId()).isEqualTo(cid);
        assertThat(captor.getValue().getWorkflow()).isEqualTo("application-tracking");
        assertThat(captor.getValue().getStage()).isEqualTo("track");
        assertThat(captor.getValue().getException()).contains("boom");
    }

    @Test
    void recordTruncatesOversizePayload() {
        String huge = "x".repeat(20_000);
        service.record(UUID.randomUUID(), "w", "s", huge, new RuntimeException(huge));
        ArgumentCaptor<WorkflowDeadLetter> captor = ArgumentCaptor.forClass(WorkflowDeadLetter.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getPayload().length()).isEqualTo(8000);
        assertThat(captor.getValue().getException().length()).isEqualTo(8000);
    }

    @Test
    void recordSwallowsNullException() {
        service.record(UUID.randomUUID(), "w", "s", null, null); // must not throw
        verify(repo).save(any());
    }

    @Test
    void recordNeverThrowsWhenPersistenceFails() {
        when(repo.save(any())).thenThrow(new RuntimeException("db down"));
        service.record(UUID.randomUUID(), "w", "s", "p", new RuntimeException("orig")); // must not throw
        assertThat(metrics.deadLetterSnapshot().get("deadLetterFailures")).isEqualTo(1L);
    }
}
