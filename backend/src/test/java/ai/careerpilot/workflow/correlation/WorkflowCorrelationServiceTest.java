package ai.careerpilot.workflow.correlation;

import ai.careerpilot.domain.WorkflowCorrelation;
import ai.careerpilot.repo.WorkflowCorrelationRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Phase 3A.0 — the correlation service is pure bookkeeping that must NEVER throw: a tracking failure
 * must not break the workflow it observes. {@code start} always returns a usable id even when
 * persistence fails; {@code advance} is a safe no-op on unknown/null ids.
 */
class WorkflowCorrelationServiceTest {

    private final WorkflowCorrelationRepository repo = mock(WorkflowCorrelationRepository.class);
    private final WorkflowMetrics metrics = new WorkflowMetrics();
    private final WorkflowCorrelationService service = new WorkflowCorrelationService(repo, metrics);

    @Test
    void startPersistsAndReturnsId() {
        UUID id = service.start("application-tracking", UUID.randomUUID(), UUID.randomUUID(), null);
        assertThat(id).isNotNull();
        verify(repo).save(any(WorkflowCorrelation.class));
    }

    @Test
    void startReturnsIdEvenWhenPersistenceFails() {
        when(repo.save(any())).thenThrow(new RuntimeException("db down"));
        UUID id = service.start("application-tracking", UUID.randomUUID(), UUID.randomUUID(), null);
        assertThat(id).isNotNull(); // caller can still propagate correlation best-effort
    }

    @Test
    void advanceNoOpsOnNullId() {
        service.advance(null, "TRACKING", WorkflowCorrelation.STATUS_IN_PROGRESS);
        verifyNoInteractions(repo);
    }

    @Test
    void advanceNoOpsWhenCorrelationUnknown() {
        UUID id = UUID.randomUUID();
        when(repo.findByCorrelationId(id)).thenReturn(Optional.empty());
        service.advance(id, "TRACKING", WorkflowCorrelation.STATUS_IN_PROGRESS);
        verify(repo, never()).save(any());
    }

    @Test
    void advanceUpdatesStageAndStatus() {
        UUID id = UUID.randomUUID();
        WorkflowCorrelation row = WorkflowCorrelation.builder()
                .correlationId(id).status(WorkflowCorrelation.STATUS_STARTED).build();
        when(repo.findByCorrelationId(id)).thenReturn(Optional.of(row));
        service.advance(id, "ANALYTICS", WorkflowCorrelation.STATUS_COMPLETED);
        assertThat(row.getWorkflowStage()).isEqualTo("ANALYTICS");
        assertThat(row.getStatus()).isEqualTo(WorkflowCorrelation.STATUS_COMPLETED);
        verify(repo).save(row);
    }

    @Test
    void advanceNeverThrowsOnRepoFailure() {
        UUID id = UUID.randomUUID();
        when(repo.findByCorrelationId(id)).thenThrow(new RuntimeException("boom"));
        service.advance(id, "TRACKING", WorkflowCorrelation.STATUS_IN_PROGRESS); // must not throw
    }
}
