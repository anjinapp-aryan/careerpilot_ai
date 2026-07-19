package ai.careerpilot.execution;

import ai.careerpilot.domain.ApprovalQueueEntry;
import ai.careerpilot.execution.analytics.AnalyticsService;
import ai.careerpilot.execution.analytics.AnalyticsWorker;
import ai.careerpilot.execution.approval.ApprovalService;
import ai.careerpilot.execution.approval.ApprovalWorker;
import ai.careerpilot.execution.event.ApplicationSubmittedEvent;
import ai.careerpilot.execution.event.ApprovalGrantedEvent;
import ai.careerpilot.execution.event.SafetyValidatedEvent;
import ai.careerpilot.execution.execution.ApplicationExecutionService;
import ai.careerpilot.execution.execution.ApplicationExecutionWorker;
import ai.careerpilot.execution.execution.FormApprovalExecutionWorker;
import ai.careerpilot.execution.safety.SafetyEngine;
import ai.careerpilot.execution.safety.SafetyResult;
import ai.careerpilot.execution.safety.SafetyValidationWorker;
import ai.careerpilot.execution.safety.SafetyVerdict;
import ai.careerpilot.execution.tracking.ApplicationTrackingService;
import ai.careerpilot.execution.tracking.TrackingWorker;
import ai.careerpilot.resumetailoring.event.ApplicationPackageReadyEvent;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Optional;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Phase 2E — every pipeline worker must obey the same contract: fire only when BOTH its trigger flag
 * and the underlying engine are on, run on its dedicated bounded executor, and never propagate a
 * failure. These tests use an inline executor (runs the submitted task synchronously) so the
 * downstream call is observable.
 */
class ExecutionWorkersTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();
    private final UUID pkgId = UUID.randomUUID();

    /** A ThreadPoolTaskExecutor whose execute() runs the task inline, for deterministic assertions. */
    private ThreadPoolTaskExecutor inlineExecutor() {
        ThreadPoolTaskExecutor exec = mock(ThreadPoolTaskExecutor.class);
        doAnswer(inv -> { ((Runnable) inv.getArgument(0)).run(); return null; }).when(exec).execute(any());
        return exec;
    }

    // ── SafetyValidationWorker (pipeline entry) ──

    @Test
    void safetyWorkerNoOpsWhenTriggerOff() {
        SafetyEngine engine = mock(SafetyEngine.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        when(engine.isEnabled()).thenReturn(true);
        new SafetyValidationWorker(engine, events, inlineExecutor(), false)
                .onApplicationPackageReady(new ApplicationPackageReadyEvent(userId, jobId, pkgId, 1));
        verifyNoInteractions(events);
        verify(engine, never()).evaluate(any(), any(), any());
    }

    @Test
    void safetyWorkerPublishesOnNonBlockVerdict() {
        SafetyEngine engine = mock(SafetyEngine.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        when(engine.isEnabled()).thenReturn(true);
        when(engine.evaluate(userId, jobId, pkgId))
                .thenReturn(new SafetyResult(SafetyVerdict.SAFE, List.of()));
        new SafetyValidationWorker(engine, events, inlineExecutor(), true)
                .onApplicationPackageReady(new ApplicationPackageReadyEvent(userId, jobId, pkgId, 1));
        verify(events).publishEvent(any(SafetyValidatedEvent.class));
    }

    @Test
    void safetyWorkerEndsChainOnBlock() {
        SafetyEngine engine = mock(SafetyEngine.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        when(engine.isEnabled()).thenReturn(true);
        when(engine.evaluate(userId, jobId, pkgId))
                .thenReturn(new SafetyResult(SafetyVerdict.BLOCK, List.of("no cover letter")));
        new SafetyValidationWorker(engine, events, inlineExecutor(), true)
                .onApplicationPackageReady(new ApplicationPackageReadyEvent(userId, jobId, pkgId, 1));
        verify(events, never()).publishEvent(any());
    }

    @Test
    void safetyWorkerNoOpsWhenEngineDisabled() {
        SafetyEngine engine = mock(SafetyEngine.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        when(engine.isEnabled()).thenReturn(false);
        new SafetyValidationWorker(engine, events, inlineExecutor(), true)
                .onApplicationPackageReady(new ApplicationPackageReadyEvent(userId, jobId, pkgId, 1));
        verifyNoInteractions(events);
    }

    // ── ApprovalWorker ──

    @Test
    void approvalWorkerEnqueuesWhenEnabled() {
        ApprovalService approval = mock(ApprovalService.class);
        when(approval.isEnabled()).thenReturn(true);
        new ApprovalWorker(approval, inlineExecutor())
                .onSafetyValidated(new SafetyValidatedEvent(userId, jobId, pkgId, "SAFE"));
        verify(approval).enqueue(userId, jobId, pkgId, "SAFE");
    }

    @Test
    void approvalWorkerNoOpsWhenDisabled() {
        ApprovalService approval = mock(ApprovalService.class);
        when(approval.isEnabled()).thenReturn(false);
        new ApprovalWorker(approval, inlineExecutor())
                .onSafetyValidated(new SafetyValidatedEvent(userId, jobId, pkgId, "SAFE"));
        verify(approval, never()).enqueue(any(), any(), any(), any());
    }

    // ── ApplicationExecutionWorker ──

    @Test
    void executionWorkerRunsWhenTriggerAndEngineOn() {
        ApplicationExecutionService execution = mock(ApplicationExecutionService.class);
        ApprovalService approvalService = mock(ApprovalService.class);
        when(execution.isEnabled()).thenReturn(true);
        UUID approvalId = UUID.randomUUID();
        when(approvalService.findById(approvalId)).thenReturn(Optional.empty());
        new ApplicationExecutionWorker(execution, approvalService, inlineExecutor(), true)
                .onApprovalGranted(new ApprovalGrantedEvent(userId, jobId, pkgId, approvalId, "boss"));
        verify(execution).execute(userId, jobId, pkgId);
    }

    @Test
    void executionWorkerNoOpsWhenTriggerOff() {
        ApplicationExecutionService execution = mock(ApplicationExecutionService.class);
        ApprovalService approvalService = mock(ApprovalService.class);
        when(execution.isEnabled()).thenReturn(true);
        new ApplicationExecutionWorker(execution, approvalService, inlineExecutor(), false)
                .onApprovalGranted(new ApprovalGrantedEvent(userId, jobId, pkgId, UUID.randomUUID(), "boss"));
        verify(execution, never()).execute(any(), any(), any());
    }

    @Test
    void executionWorkerNoOpsWhenEngineDisabled() {
        ApplicationExecutionService execution = mock(ApplicationExecutionService.class);
        ApprovalService approvalService = mock(ApprovalService.class);
        when(execution.isEnabled()).thenReturn(false);
        new ApplicationExecutionWorker(execution, approvalService, inlineExecutor(), true)
                .onApprovalGranted(new ApprovalGrantedEvent(userId, jobId, pkgId, UUID.randomUUID(), "boss"));
        verify(execution, never()).execute(any(), any(), any());
    }

    @Test
    void executionWorkerSkipsFormScreenshotApprovals_ownedByFormApprovalExecutionWorker() {
        ApplicationExecutionService execution = mock(ApplicationExecutionService.class);
        ApprovalService approvalService = mock(ApprovalService.class);
        when(execution.isEnabled()).thenReturn(true);
        UUID approvalId = UUID.randomUUID();
        ApprovalQueueEntry formEntry = ApprovalQueueEntry.builder()
                .id(approvalId).approvalType(ApprovalQueueEntry.TYPE_FORM_SCREENSHOT).build();
        when(approvalService.findById(approvalId)).thenReturn(Optional.of(formEntry));
        new ApplicationExecutionWorker(execution, approvalService, inlineExecutor(), true)
                .onApprovalGranted(new ApprovalGrantedEvent(userId, jobId, pkgId, approvalId, "boss"));
        verify(execution, never()).execute(any(), any(), any());
    }

    // ── FormApprovalExecutionWorker (Gap D) ──

    @Test
    void formApprovalWorkerFinalizesSubmitOnlyForFormScreenshotApprovals() {
        ApprovalService approvalService = mock(ApprovalService.class);
        ApplicationExecutionService execution = mock(ApplicationExecutionService.class);
        when(execution.isEnabled()).thenReturn(true);
        UUID approvalId = UUID.randomUUID();
        UUID executionId = UUID.randomUUID();
        ApprovalQueueEntry formEntry = ApprovalQueueEntry.builder()
                .id(approvalId).approvalType(ApprovalQueueEntry.TYPE_FORM_SCREENSHOT).executionId(executionId).build();
        when(approvalService.findById(approvalId)).thenReturn(Optional.of(formEntry));
        new FormApprovalExecutionWorker(approvalService, execution, inlineExecutor())
                .onApprovalGranted(new ApprovalGrantedEvent(userId, jobId, pkgId, approvalId, "boss"));
        verify(execution).finalizeGuestApplySubmit(executionId);
    }

    @Test
    void formApprovalWorkerIgnoresPackageLevelApprovals() {
        ApprovalService approvalService = mock(ApprovalService.class);
        ApplicationExecutionService execution = mock(ApplicationExecutionService.class);
        when(execution.isEnabled()).thenReturn(true);
        UUID approvalId = UUID.randomUUID();
        ApprovalQueueEntry pkgEntry = ApprovalQueueEntry.builder()
                .id(approvalId).approvalType(ApprovalQueueEntry.TYPE_APPLICATION_PACKAGE).build();
        when(approvalService.findById(approvalId)).thenReturn(Optional.of(pkgEntry));
        new FormApprovalExecutionWorker(approvalService, execution, inlineExecutor())
                .onApprovalGranted(new ApprovalGrantedEvent(userId, jobId, pkgId, approvalId, "boss"));
        verify(execution, never()).finalizeGuestApplySubmit(any());
    }

    @Test
    void formApprovalWorkerNoOpsWhenEngineDisabled() {
        ApprovalService approvalService = mock(ApprovalService.class);
        ApplicationExecutionService execution = mock(ApplicationExecutionService.class);
        when(execution.isEnabled()).thenReturn(false);
        new FormApprovalExecutionWorker(approvalService, execution, inlineExecutor())
                .onApprovalGranted(new ApprovalGrantedEvent(userId, jobId, pkgId, UUID.randomUUID(), "boss"));
        verify(execution, never()).finalizeGuestApplySubmit(any());
        verifyNoInteractions(approvalService);
    }

    // ── TrackingWorker + AnalyticsWorker (fed by ApplicationSubmittedEvent, never emitted in 2E) ──

    @Test
    void trackingWorkerRecordsSubmittedWhenEnabled() {
        ApplicationTrackingService tracking = mock(ApplicationTrackingService.class);
        when(tracking.isEnabled()).thenReturn(true);
        UUID execId = UUID.randomUUID();
        new TrackingWorker(tracking, inlineExecutor(), true)
                .onApplicationSubmitted(new ApplicationSubmittedEvent(userId, jobId, execId, null, "greenhouse"));
        verify(tracking).record(eq(execId), eq(userId), eq(jobId), any(), anyString(), anyString());
    }

    @Test
    void trackingWorkerNoOpsWhenTriggerOff() {
        ApplicationTrackingService tracking = mock(ApplicationTrackingService.class);
        when(tracking.isEnabled()).thenReturn(true);
        new TrackingWorker(tracking, inlineExecutor(), false)
                .onApplicationSubmitted(new ApplicationSubmittedEvent(userId, jobId, UUID.randomUUID(), null, "x"));
        verify(tracking, never()).record(any(), any(), any(), any(), any(), any());
    }

    @Test
    void analyticsWorkerRecordsWhenEnabled() {
        AnalyticsService analytics = mock(AnalyticsService.class);
        when(analytics.isEnabled()).thenReturn(true);
        new AnalyticsWorker(analytics, inlineExecutor(), true)
                .onApplicationSubmitted(new ApplicationSubmittedEvent(userId, jobId, UUID.randomUUID(), null, "x"));
        verify(analytics).record(eq(userId), anyString(), any());
    }

    @Test
    void analyticsWorkerNoOpsWhenDisabled() {
        AnalyticsService analytics = mock(AnalyticsService.class);
        when(analytics.isEnabled()).thenReturn(false);
        new AnalyticsWorker(analytics, inlineExecutor(), true)
                .onApplicationSubmitted(new ApplicationSubmittedEvent(userId, jobId, UUID.randomUUID(), null, "x"));
        verify(analytics, never()).record(any(), any(), any());
    }

    // ── failure isolation: a throwing executor must never propagate out of any worker ──

    @Test
    void workersNeverPropagateExecutorFailure() {
        ThreadPoolTaskExecutor boom = mock(ThreadPoolTaskExecutor.class);
        doThrow(new RuntimeException("queue full")).when(boom).execute(any());

        ApplicationExecutionService execution = mock(ApplicationExecutionService.class);
        ApprovalService approvalService = mock(ApprovalService.class);
        when(execution.isEnabled()).thenReturn(true);
        // must not throw
        new ApplicationExecutionWorker(execution, approvalService, boom, true)
                .onApprovalGranted(new ApprovalGrantedEvent(userId, jobId, pkgId, UUID.randomUUID(), "boss"));

        ApprovalService approval = mock(ApprovalService.class);
        when(approval.isEnabled()).thenReturn(true);
        new ApprovalWorker(approval, boom)
                .onSafetyValidated(new SafetyValidatedEvent(userId, jobId, pkgId, "SAFE"));

        // FormApprovalExecutionWorker must also never propagate a saturated-executor failure.
        ApprovalQueueEntry formEntry = ApprovalQueueEntry.builder()
                .id(UUID.randomUUID()).approvalType(ApprovalQueueEntry.TYPE_FORM_SCREENSHOT)
                .executionId(UUID.randomUUID()).build();
        when(approvalService.findById(any())).thenReturn(Optional.of(formEntry));
        new FormApprovalExecutionWorker(approvalService, execution, boom)
                .onApprovalGranted(new ApprovalGrantedEvent(userId, jobId, pkgId, UUID.randomUUID(), "boss"));
    }
}
