package ai.careerpilot.execution.execution;

import ai.careerpilot.domain.ApprovalQueueEntry;
import ai.careerpilot.execution.approval.ApprovalService;
import ai.careerpilot.execution.browser.multistep.MultiStepExecutionOrchestrator;
import ai.careerpilot.execution.event.ApprovalGrantedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Phase F3 — the approval seam.
 *
 * <p>The single question these tests answer is whether an approval reaches the right runtime. The
 * risk being guarded is asymmetric and severe in one direction: a single-page approval that
 * mistakenly routed into the multi-step path would stop submitting real applications, and a
 * multi-step approval that routed into {@code finalizeSubmit} would <b>click submit on a wizard
 * whose later pages nobody has reviewed</b>. Both directions are pinned.
 */
class FormApprovalMultiStepRoutingTest {

    private ApprovalService approvalService;
    private ApplicationExecutionService executionService;
    private ThreadPoolTaskExecutor executor;
    private MultiStepExecutionOrchestrator orchestrator;
    private ObjectProvider<MultiStepExecutionOrchestrator> provider;

    private final UUID approvalId = UUID.randomUUID();
    private final UUID executionId = UUID.randomUUID();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        approvalService = mock(ApprovalService.class);
        executionService = mock(ApplicationExecutionService.class);
        executor = mock(ThreadPoolTaskExecutor.class);
        orchestrator = mock(MultiStepExecutionOrchestrator.class);
        provider = mock(ObjectProvider.class);

        when(executionService.isEnabled()).thenReturn(true);
        when(provider.getIfAvailable()).thenReturn(orchestrator);
        // Run dispatched work inline so the routing decision is observable.
        doAnswer(inv -> {
            inv.getArgument(0, Runnable.class).run();
            return null;
        }).when(executor).execute(any(Runnable.class));

        ApprovalQueueEntry entry = ApprovalQueueEntry.builder()
                .id(approvalId)
                .approvalType(ApprovalQueueEntry.TYPE_FORM_SCREENSHOT)
                .executionId(executionId)
                .build();
        when(approvalService.findById(approvalId)).thenReturn(Optional.of(entry));
    }

    private ApprovalGrantedEvent event() {
        return new ApprovalGrantedEvent(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                approvalId, "reviewer");
    }

    private FormApprovalExecutionWorker worker() {
        return new FormApprovalExecutionWorker(approvalService, executionService, executor, provider);
    }

    @Test
    @DisplayName("a single-page approval still finalises the submit — unchanged behaviour")
    void singlePageApprovalStillFinalises() {
        when(orchestrator.isMultiStepApproval(approvalId)).thenReturn(false);

        worker().onApprovalGranted(event());

        verify(executionService).finalizeGuestApplySubmit(executionId);
        verify(executionService, never()).resumeMultiStepAfterApproval(any(), any());
    }

    @Test
    @DisplayName("a multi-step approval advances the wizard and NEVER finalises a submit")
    void multiStepApprovalAdvancesInstead() {
        when(orchestrator.isMultiStepApproval(approvalId)).thenReturn(true);

        worker().onApprovalGranted(event());

        verify(executionService).resumeMultiStepAfterApproval(approvalId, executionId);
        // The critical assertion: submit is not clicked on a wizard with unreviewed pages ahead.
        verify(executionService, never()).finalizeGuestApplySubmit(any());
    }

    @Test
    @DisplayName("with the orchestrator bean absent, routing is the pre-F3 path")
    void absentOrchestratorFallsBack() {
        when(provider.getIfAvailable()).thenReturn(null);

        worker().onApprovalGranted(event());

        verify(executionService).finalizeGuestApplySubmit(executionId);
    }

    @Test
    @DisplayName("a non-form approval subtype is ignored entirely, as before")
    void otherApprovalTypesUntouched() {
        when(approvalService.findById(approvalId)).thenReturn(Optional.of(
                ApprovalQueueEntry.builder().id(approvalId)
                        .approvalType(ApprovalQueueEntry.TYPE_APPLICATION_PACKAGE)
                        .executionId(executionId).build()));

        worker().onApprovalGranted(event());

        verify(executionService, never()).finalizeGuestApplySubmit(any());
        verify(executionService, never()).resumeMultiStepAfterApproval(any(), any());
        verifyNoInteractions(executor);
    }

    @Test
    @DisplayName("execution disabled short-circuits before anything is looked up")
    void disabledExecutionDoesNothing() {
        when(executionService.isEnabled()).thenReturn(false);

        worker().onApprovalGranted(event());

        verifyNoInteractions(approvalService, executor);
    }

    @Test
    @DisplayName("the routing decision is made once, on the entry actually approved")
    void routingUsesTheApprovedEntry() {
        when(orchestrator.isMultiStepApproval(approvalId)).thenReturn(true);

        worker().onApprovalGranted(event());

        ArgumentCaptor<UUID> captor = ArgumentCaptor.forClass(UUID.class);
        verify(orchestrator).isMultiStepApproval(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue()).isEqualTo(approvalId);
    }
}
