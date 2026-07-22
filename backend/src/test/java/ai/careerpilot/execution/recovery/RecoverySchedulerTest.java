package ai.careerpilot.execution.recovery;

import ai.careerpilot.domain.ApplicationExecution;
import ai.careerpilot.execution.execution.ApplicationExecutionService;
import ai.careerpilot.repo.ApplicationExecutionRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Phase 7.16.3 — the Recovery Center's retry-queue poller. Pure dispatch logic; no scheduling framework involved in these tests. */
class RecoverySchedulerTest {

    private final ApplicationExecutionRepository executions = mock(ApplicationExecutionRepository.class);
    private final ApplicationExecutionService executionService = mock(ApplicationExecutionService.class);

    @Test
    void disabledTriggerNeverQueries() {
        new RecoveryScheduler(executions, executionService, false).pollDueRetries();
        verify(executions, never()).findByExecutionStatusAndNextRetryAtBefore(any(), any());
    }

    @Test
    void enabledTriggerRetriesEachDueExecution() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        when(executions.findByExecutionStatusAndNextRetryAtBefore(eq(ApplicationExecution.STATUS_RETRY), any(Instant.class)))
                .thenReturn(List.of(
                        ApplicationExecution.builder().id(id1).executionStatus(ApplicationExecution.STATUS_RETRY).build(),
                        ApplicationExecution.builder().id(id2).executionStatus(ApplicationExecution.STATUS_RETRY).build()));

        new RecoveryScheduler(executions, executionService, true).pollDueRetries();

        verify(executionService, times(1)).retryExecution(id1);
        verify(executionService, times(1)).retryExecution(id2);
    }

    @Test
    void oneFailingRetryNeverBlocksTheOthers() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        when(executions.findByExecutionStatusAndNextRetryAtBefore(eq(ApplicationExecution.STATUS_RETRY), any(Instant.class)))
                .thenReturn(List.of(
                        ApplicationExecution.builder().id(id1).executionStatus(ApplicationExecution.STATUS_RETRY).build(),
                        ApplicationExecution.builder().id(id2).executionStatus(ApplicationExecution.STATUS_RETRY).build()));
        when(executionService.retryExecution(id1)).thenThrow(new RuntimeException("boom"));

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
                new RecoveryScheduler(executions, executionService, true).pollDueRetries());

        verify(executionService, times(1)).retryExecution(id2);
    }
}
