package ai.careerpilot.packageintel;

import ai.careerpilot.resumetailoring.event.ApplicationPackageReadyEvent;
import ai.careerpilot.workflow.correlation.WorkflowCorrelationService;
import ai.careerpilot.workflow.correlation.WorkflowDeadLetterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** The worker must fire only when both flags are on, run on its executor, and isolate failures to the dead-letter sink. */
class PackageValidationWorkerTest {

    private ApplicationPackageIntelligenceService intelligence;
    private ThreadPoolTaskExecutor executor;
    private WorkflowCorrelationService correlation;
    private WorkflowDeadLetterService deadLetter;

    private final ApplicationPackageReadyEvent event =
            new ApplicationPackageReadyEvent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1);

    @BeforeEach
    void setUp() {
        intelligence = mock(ApplicationPackageIntelligenceService.class);
        executor = mock(ThreadPoolTaskExecutor.class);
        correlation = mock(WorkflowCorrelationService.class);
        deadLetter = mock(WorkflowDeadLetterService.class);
        when(correlation.start(any(), any(), any(), any())).thenReturn(UUID.randomUUID());
        // run submitted tasks inline.
        doAnswer(inv -> { ((Runnable) inv.getArgument(0)).run(); return null; }).when(executor).execute(any());
    }

    private PackageValidationWorker worker(boolean trigger, boolean engineEnabled) {
        when(intelligence.isEnabled()).thenReturn(engineEnabled);
        return new PackageValidationWorker(intelligence, executor, correlation, deadLetter, trigger);
    }

    @Test
    void darkByDefaultDoesNothing() {
        worker(false, false).onApplicationPackageReady(event);
        verifyNoInteractions(executor);
        verify(intelligence, never()).enrichAndValidate(any(), any());
    }

    @Test
    void triggerOnButEngineOffDoesNothing() {
        worker(true, false).onApplicationPackageReady(event);
        verify(intelligence, never()).enrichAndValidate(any(), any());
    }

    @Test
    void bothOnValidatesOnExecutor() {
        worker(true, true).onApplicationPackageReady(event);
        verify(executor).execute(any());
        verify(intelligence).enrichAndValidate(eq(event.applicationPackageId()), any());
    }

    @Test
    void taskFailureIsDeadLetteredNotThrown() {
        PackageValidationWorker w = worker(true, true);
        when(intelligence.enrichAndValidate(any(), any())).thenThrow(new RuntimeException("boom"));
        w.onApplicationPackageReady(event); // must not throw
        verify(deadLetter).record(any(), eq("APPLICATION_PACKAGE"), eq("PACKAGE_VALIDATION"), any(), any());
    }
}
