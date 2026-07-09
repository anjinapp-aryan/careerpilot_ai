package ai.careerpilot.workflow.tracking;

import ai.careerpilot.domain.ApplicationLifecycle;
import ai.careerpilot.repo.ApplicationLifecycleAuditRepository;
import ai.careerpilot.repo.ApplicationLifecycleRepository;
import ai.careerpilot.repo.ApplicationStatusHistoryRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Phase 3A.1 — the lifecycle service ships DARK (disabled → pure no-op), opens a row at SUBMITTED,
 * validates transitions through the state machine (refusing illegal ones without throwing), and never
 * throws on a persistence failure — so an async worker can call it safely.
 */
class ApplicationLifecycleServiceTest {

    private final ApplicationLifecycleRepository lifecycles = mock(ApplicationLifecycleRepository.class);
    private final ApplicationStatusHistoryRepository history = mock(ApplicationStatusHistoryRepository.class);
    private final ApplicationLifecycleAuditRepository audit = mock(ApplicationLifecycleAuditRepository.class);
    private final WorkflowTrackingMetrics metrics = new WorkflowTrackingMetrics();

    private final UUID userId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();

    private ApplicationLifecycleService enabled() {
        return new ApplicationLifecycleService(lifecycles, history, audit, metrics, true);
    }

    private ApplicationLifecycleService disabled() {
        return new ApplicationLifecycleService(lifecycles, history, audit, metrics, false);
    }

    @Test
    void disabledIsNoOp() {
        assertThat(disabled().createOrGet(userId, jobId, null, "Acme", "US", "seed")).isEmpty();
        assertThat(disabled().transition(userId, jobId, ApplicationLifecycle.STATUS_VIEWED, null)).isEmpty();
        verifyNoInteractions(lifecycles);
    }

    @Test
    void createOpensRowAtSubmitted() {
        when(lifecycles.findByUserIdAndJobId(userId, jobId)).thenReturn(Optional.empty());
        when(lifecycles.save(any())).thenAnswer(inv -> {
            ApplicationLifecycle r = inv.getArgument(0);
            r.setId(UUID.randomUUID());
            return r;
        });
        Optional<ApplicationLifecycle> row = enabled().createOrGet(userId, jobId, null, "Acme", "US", "seed");
        assertThat(row).isPresent();
        assertThat(row.get().getCurrentStatus()).isEqualTo(ApplicationLifecycle.STATUS_SUBMITTED);
        verify(history).save(any());
        verify(audit).save(any());
    }

    @Test
    void createIsIdempotentWhenRowExists() {
        ApplicationLifecycle existing = ApplicationLifecycle.builder()
                .id(UUID.randomUUID()).userId(userId).jobId(jobId)
                .currentStatus(ApplicationLifecycle.STATUS_VIEWED).build();
        when(lifecycles.findByUserIdAndJobId(userId, jobId)).thenReturn(Optional.of(existing));
        Optional<ApplicationLifecycle> row = enabled().createOrGet(userId, jobId, null, "Acme", "US", "seed");
        assertThat(row).containsSame(existing);
        verify(lifecycles, never()).save(any());
    }

    @Test
    void validTransitionApplied() {
        ApplicationLifecycle row = ApplicationLifecycle.builder()
                .id(UUID.randomUUID()).userId(userId).jobId(jobId)
                .currentStatus(ApplicationLifecycle.STATUS_SUBMITTED).build();
        when(lifecycles.findByUserIdAndJobId(userId, jobId)).thenReturn(Optional.of(row));
        when(lifecycles.save(any())).thenAnswer(inv -> inv.getArgument(0));
        Optional<ApplicationLifecycle> out = enabled().transition(userId, jobId, ApplicationLifecycle.STATUS_VIEWED, "seen");
        assertThat(out).isPresent();
        assertThat(out.get().getCurrentStatus()).isEqualTo(ApplicationLifecycle.STATUS_VIEWED);
        assertThat(out.get().getPreviousStatus()).isEqualTo(ApplicationLifecycle.STATUS_SUBMITTED);
    }

    @Test
    void illegalTransitionRefusedNotThrown() {
        ApplicationLifecycle row = ApplicationLifecycle.builder()
                .id(UUID.randomUUID()).userId(userId).jobId(jobId)
                .currentStatus(ApplicationLifecycle.STATUS_SUBMITTED).build();
        when(lifecycles.findByUserIdAndJobId(userId, jobId)).thenReturn(Optional.of(row));
        Optional<ApplicationLifecycle> out = enabled().transition(userId, jobId, ApplicationLifecycle.STATUS_ACCEPTED, null);
        assertThat(out).isEmpty();
        verify(lifecycles, never()).save(any());
        verify(audit).save(any()); // a REFUSED audit entry is written
    }

    @Test
    void transitionOnMissingRowIsEmpty() {
        when(lifecycles.findByUserIdAndJobId(userId, jobId)).thenReturn(Optional.empty());
        assertThat(enabled().transition(userId, jobId, ApplicationLifecycle.STATUS_VIEWED, null)).isEmpty();
    }

    @Test
    void neverThrowsOnRepoFailure() {
        when(lifecycles.findByUserIdAndJobId(any(), any())).thenThrow(new RuntimeException("db down"));
        assertThat(enabled().createOrGet(userId, jobId, null, "Acme", "US", "seed")).isEmpty();
        assertThat(enabled().transition(userId, jobId, ApplicationLifecycle.STATUS_VIEWED, null)).isEmpty();
    }
}
