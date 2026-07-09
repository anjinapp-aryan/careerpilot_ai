package ai.careerpilot.workflow;

import ai.careerpilot.domain.ApplicationEmail;
import ai.careerpilot.domain.ApplicationLifecycle;
import ai.careerpilot.domain.Interview;
import ai.careerpilot.execution.event.ApplicationSubmittedEvent;
import ai.careerpilot.workflow.analytics.AnalyticsWorker;
import ai.careerpilot.workflow.analytics.ApplicationAnalyticsService;
import ai.careerpilot.workflow.analytics.OfferDetectionWorker;
import ai.careerpilot.workflow.career.CareerIntelligenceService;
import ai.careerpilot.workflow.career.CareerIntelligenceWorker;
import ai.careerpilot.workflow.correlation.WorkflowCorrelationService;
import ai.careerpilot.workflow.correlation.WorkflowDeadLetterService;
import ai.careerpilot.workflow.email.EmailIntelligenceService;
import ai.careerpilot.workflow.email.EmailIntelligenceWorker;
import ai.careerpilot.workflow.entry.WorkflowEntryBridge;
import ai.careerpilot.workflow.event.*;
import ai.careerpilot.workflow.interview.InterviewDetectionWorker;
import ai.careerpilot.workflow.interview.InterviewService;
import ai.careerpilot.workflow.interview.InterviewTrackingWorker;
import ai.careerpilot.workflow.timeline.TimelineService;
import ai.careerpilot.workflow.timeline.TimelineWorker;
import ai.careerpilot.workflow.tracking.ApplicationLifecycleService;
import ai.careerpilot.workflow.tracking.ApplicationTrackingWorker;
import ai.careerpilot.workflow.tracking.StatusDetectionWorker;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Phase 3A — every one of the nine workflow workers must obey the same contract: fire only when BOTH its
 * trigger flag and its engine are on, run on its dedicated bounded executor, and NEVER propagate a
 * failure (a throwing executor becomes a {@code workflow_dead_letter} row instead). The entry bridge is
 * DARK by default — with stock flags it mints nothing. Inline executor makes the async body observable.
 */
class WorkflowWorkersTest {

    private final UUID correlationId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();
    private final UUID applicationId = UUID.randomUUID();

    private final WorkflowCorrelationService correlation = mock(WorkflowCorrelationService.class);
    private final WorkflowDeadLetterService deadLetter = mock(WorkflowDeadLetterService.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    /** ThreadPoolTaskExecutor whose execute() runs inline, for deterministic assertions. */
    private ThreadPoolTaskExecutor inline() {
        ThreadPoolTaskExecutor exec = mock(ThreadPoolTaskExecutor.class);
        doAnswer(inv -> { ((Runnable) inv.getArgument(0)).run(); return null; }).when(exec).execute(any());
        return exec;
    }

    private ThreadPoolTaskExecutor boom() {
        ThreadPoolTaskExecutor exec = mock(ThreadPoolTaskExecutor.class);
        doThrow(new RuntimeException("queue full")).when(exec).execute(any());
        return exec;
    }

    private ApplicationCreatedEvent created() {
        return ApplicationCreatedEvent.open(correlationId, userId, jobId, applicationId, "Acme", "US", "seed");
    }

    // ── 1. ApplicationTrackingWorker ──

    @Test
    void trackingWorkerFiresWhenTriggerAndEngineOn() {
        ApplicationLifecycleService lifecycle = mock(ApplicationLifecycleService.class);
        when(lifecycle.isEnabled()).thenReturn(true);
        new ApplicationTrackingWorker(lifecycle, correlation, deadLetter, events, inline(), true)
                .onApplicationCreated(created());
        verify(lifecycle).createOrGet(userId, jobId, applicationId, "Acme", "US", "seed");
        verify(events).publishEvent(any(ApplicationTrackedEvent.class));
    }

    @Test
    void trackingWorkerNoOpsWhenTriggerOff() {
        ApplicationLifecycleService lifecycle = mock(ApplicationLifecycleService.class);
        when(lifecycle.isEnabled()).thenReturn(true);
        new ApplicationTrackingWorker(lifecycle, correlation, deadLetter, events, inline(), false)
                .onApplicationCreated(created());
        verifyNoInteractions(events);
        verify(lifecycle, never()).createOrGet(any(), any(), any(), any(), any(), any());
    }

    @Test
    void trackingWorkerNoOpsWhenEngineOff() {
        ApplicationLifecycleService lifecycle = mock(ApplicationLifecycleService.class);
        when(lifecycle.isEnabled()).thenReturn(false);
        new ApplicationTrackingWorker(lifecycle, correlation, deadLetter, events, inline(), true)
                .onApplicationCreated(created());
        verifyNoInteractions(events);
    }

    @Test
    void trackingWorkerDeadLettersExecutorFailure() {
        ApplicationLifecycleService lifecycle = mock(ApplicationLifecycleService.class);
        when(lifecycle.isEnabled()).thenReturn(true);
        new ApplicationTrackingWorker(lifecycle, correlation, deadLetter, events, boom(), true)
                .onApplicationCreated(created()); // must not throw
        verify(deadLetter).record(eq(correlationId), anyString(), anyString(), any(), any());
    }

    // ── 2. StatusDetectionWorker ──

    @Test
    void statusDetectionPublishesDetectedStatus() {
        ApplicationLifecycleService lifecycle = mock(ApplicationLifecycleService.class);
        when(lifecycle.isEnabled()).thenReturn(true);
        when(lifecycle.find(userId, jobId)).thenReturn(Optional.of(ApplicationLifecycle.builder()
                .currentStatus(ApplicationLifecycle.STATUS_SUBMITTED).build()));
        new StatusDetectionWorker(lifecycle, correlation, deadLetter, events, inline(), true)
                .onApplicationTracked(ApplicationTrackedEvent.from(created(), ApplicationLifecycle.STATUS_SUBMITTED));
        verify(events).publishEvent(any(StatusDetectedEvent.class));
    }

    @Test
    void statusDetectionNoOpsWhenEngineOff() {
        ApplicationLifecycleService lifecycle = mock(ApplicationLifecycleService.class);
        when(lifecycle.isEnabled()).thenReturn(false);
        new StatusDetectionWorker(lifecycle, correlation, deadLetter, events, inline(), true)
                .onApplicationTracked(ApplicationTrackedEvent.from(created(), "SUBMITTED"));
        verifyNoInteractions(events);
    }

    // ── 3. TimelineWorker ──

    @Test
    void timelineWorkerAppendsAndPublishes() {
        TimelineService timeline = mock(TimelineService.class);
        when(timeline.isEnabled()).thenReturn(true);
        StatusDetectedEvent ev = StatusDetectedEvent.from(created(), "VIEWED", "SUBMITTED");
        new TimelineWorker(timeline, correlation, deadLetter, events, inline(), true).onStatusDetected(ev);
        verify(timeline).append(eq(userId), eq(jobId), eq("VIEWED"), anyString(), any(), anyString());
        verify(events).publishEvent(any(TimelineUpdatedEvent.class));
    }

    @Test
    void timelineWorkerNoOpsWhenTriggerOff() {
        TimelineService timeline = mock(TimelineService.class);
        when(timeline.isEnabled()).thenReturn(true);
        new TimelineWorker(timeline, correlation, deadLetter, events, inline(), false)
                .onStatusDetected(StatusDetectedEvent.from(created(), "VIEWED", "SUBMITTED"));
        verifyNoInteractions(events);
    }

    // ── 4. EmailIntelligenceWorker (neutral pass-through, no mailbox) ──

    @Test
    void emailWorkerPublishesNeutralEvent() {
        EmailIntelligenceService email = mock(EmailIntelligenceService.class);
        when(email.isEnabled()).thenReturn(true);
        new EmailIntelligenceWorker(email, correlation, deadLetter, events, inline(), true)
                .onTimelineUpdated(TimelineUpdatedEvent.from(created(), "VIEWED"));
        verify(events).publishEvent(any(EmailProcessedEvent.class));
    }

    // ── 5. InterviewDetectionWorker ──

    @Test
    void interviewDetectionMarksAndPublishesOnInterviewEmail() {
        InterviewService interview = mock(InterviewService.class);
        when(interview.isEnabled()).thenReturn(true);
        EmailProcessedEvent ev = EmailProcessedEvent.from(created(), ApplicationEmail.CATEGORY_INTERVIEW, 0.8);
        new InterviewDetectionWorker(interview, correlation, deadLetter, events, inline(), true).onEmailProcessed(ev);
        verify(interview).markDetected();
        verify(events).publishEvent(any(InterviewDetectedEvent.class));
    }

    @Test
    void interviewDetectionPublishesNoneWhenNotInterview() {
        InterviewService interview = mock(InterviewService.class);
        when(interview.isEnabled()).thenReturn(true);
        EmailProcessedEvent ev = EmailProcessedEvent.from(created(), ApplicationEmail.CATEGORY_UNKNOWN, 0.0);
        new InterviewDetectionWorker(interview, correlation, deadLetter, events, inline(), true).onEmailProcessed(ev);
        verify(interview, never()).markDetected();
        verify(events).publishEvent(any(InterviewDetectedEvent.class)); // still flows
    }

    // ── 6. InterviewTrackingWorker ──

    @Test
    void interviewTrackingRecordsWhenTypeDetected() {
        InterviewService interview = mock(InterviewService.class);
        when(interview.isEnabled()).thenReturn(true);
        when(interview.record(any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(Interview.builder().id(UUID.randomUUID()).result(Interview.RESULT_SCHEDULED).build()));
        InterviewDetectedEvent ev = InterviewDetectedEvent.from(created(), Interview.TYPE_TECHNICAL);
        new InterviewTrackingWorker(interview, correlation, deadLetter, events, inline(), true).onInterviewDetected(ev);
        verify(interview).record(eq(userId), eq(jobId), eq(Interview.TYPE_TECHNICAL), any(), any(), anyString());
        verify(events).publishEvent(any(InterviewTrackedEvent.class));
    }

    @Test
    void interviewTrackingSkipsRecordOnNone() {
        InterviewService interview = mock(InterviewService.class);
        when(interview.isEnabled()).thenReturn(true);
        InterviewDetectedEvent ev = InterviewDetectedEvent.from(created(), "NONE");
        new InterviewTrackingWorker(interview, correlation, deadLetter, events, inline(), true).onInterviewDetected(ev);
        verify(interview, never()).record(any(), any(), any(), any(), any(), any());
        verify(events).publishEvent(any(InterviewTrackedEvent.class)); // workflow still flows
    }

    // ── 7. OfferDetectionWorker (never fabricates an offer) ──

    private OfferDetectionWorker offerWorker(ApplicationLifecycleService lifecycle, boolean trigger) {
        ApplicationAnalyticsService analytics = mock(ApplicationAnalyticsService.class);
        when(analytics.isEnabled()).thenReturn(true);
        return new OfferDetectionWorker(lifecycle, analytics, correlation, deadLetter, events, inline(), trigger);
    }

    @Test
    void offerDetectionEmitsOfferWhenLifecycleOfferReceived() {
        ApplicationLifecycleService lifecycle = mock(ApplicationLifecycleService.class);
        when(lifecycle.find(userId, jobId)).thenReturn(Optional.of(ApplicationLifecycle.builder()
                .currentStatus(ApplicationLifecycle.STATUS_OFFER_RECEIVED).build()));
        offerWorker(lifecycle, true).onInterviewTracked(InterviewTrackedEvent.from(created(), null, "PENDING"));
        verify(events).publishEvent(any(OfferReceivedEvent.class));
    }

    @Test
    void offerDetectionEmitsRejectedWhenLifecycleRejected() {
        ApplicationLifecycleService lifecycle = mock(ApplicationLifecycleService.class);
        when(lifecycle.find(userId, jobId)).thenReturn(Optional.of(ApplicationLifecycle.builder()
                .currentStatus(ApplicationLifecycle.STATUS_REJECTED).build()));
        offerWorker(lifecycle, true).onInterviewTracked(InterviewTrackedEvent.from(created(), null, "PENDING"));
        verify(events).publishEvent(any(ApplicationRejectedEvent.class));
    }

    @Test
    void offerDetectionEmitsNothingWhenStillInProgress() {
        ApplicationLifecycleService lifecycle = mock(ApplicationLifecycleService.class);
        when(lifecycle.find(userId, jobId)).thenReturn(Optional.of(ApplicationLifecycle.builder()
                .currentStatus(ApplicationLifecycle.STATUS_TECHNICAL_INTERVIEW).build()));
        offerWorker(lifecycle, true).onInterviewTracked(InterviewTrackedEvent.from(created(), null, "PENDING"));
        verify(events, never()).publishEvent(any()); // does NOT fabricate a terminal outcome
    }

    // ── 8. AnalyticsWorker ──

    @Test
    void analyticsWorkerRecomputesAndPublishesOnOffer() {
        ApplicationAnalyticsService analytics = mock(ApplicationAnalyticsService.class);
        when(analytics.isEnabled()).thenReturn(true);
        new AnalyticsWorker(analytics, correlation, deadLetter, events, inline(), true)
                .onOfferReceived(OfferReceivedEvent.from(created(), null));
        verify(analytics).recompute(userId);
        verify(events).publishEvent(any(AnalyticsComputedEvent.class));
    }

    @Test
    void analyticsWorkerNoOpsWhenEngineOff() {
        ApplicationAnalyticsService analytics = mock(ApplicationAnalyticsService.class);
        when(analytics.isEnabled()).thenReturn(false);
        new AnalyticsWorker(analytics, correlation, deadLetter, events, inline(), true)
                .onApplicationRejected(ApplicationRejectedEvent.from(created(), "x"));
        verifyNoInteractions(events);
    }

    // ── 9. CareerIntelligenceWorker (terminal — publishes nothing, closes correlation) ──

    @Test
    void careerWorkerRecomputesAndCompletesCorrelation() {
        CareerIntelligenceService career = mock(CareerIntelligenceService.class);
        when(career.isEnabled()).thenReturn(true);
        new CareerIntelligenceWorker(career, correlation, deadLetter, inline(), true)
                .onAnalyticsComputed(AnalyticsComputedEvent.from(created()));
        verify(career).recompute(userId);
        verify(correlation).advance(eq(correlationId), anyString(), eq(ai.careerpilot.domain.WorkflowCorrelation.STATUS_COMPLETED));
    }

    @Test
    void careerWorkerNoOpsWhenTriggerOff() {
        CareerIntelligenceService career = mock(CareerIntelligenceService.class);
        when(career.isEnabled()).thenReturn(true);
        new CareerIntelligenceWorker(career, correlation, deadLetter, inline(), false)
                .onAnalyticsComputed(AnalyticsComputedEvent.from(created()));
        verify(career, never()).recompute(any());
    }

    // ── Entry bridge: DARK by default ──

    @Test
    void entryBridgeDarkByDefaultMintsNothing() {
        WorkflowEntryBridge bridge = new WorkflowEntryBridge(correlation, events, false);
        bridge.onApplicationSubmitted(new ApplicationSubmittedEvent(userId, jobId, UUID.randomUUID(), applicationId, "greenhouse"));
        verifyNoInteractions(correlation);
        verifyNoInteractions(events);
    }

    @Test
    void entryBridgeSeedReturnsNullWhenDark() {
        WorkflowEntryBridge bridge = new WorkflowEntryBridge(correlation, events, false);
        org.junit.jupiter.api.Assertions.assertNull(bridge.seed(userId, jobId, applicationId, "Acme", "US", "seed"));
        verifyNoInteractions(events);
    }

    @Test
    void entryBridgeMintsAndPublishesWhenEnabled() {
        when(correlation.start(anyString(), any(), any(), any())).thenReturn(correlationId);
        WorkflowEntryBridge bridge = new WorkflowEntryBridge(correlation, events, true);
        UUID id = bridge.seed(userId, jobId, applicationId, "Acme", "US", "seed");
        org.junit.jupiter.api.Assertions.assertEquals(correlationId, id);
        verify(events).publishEvent(any(ApplicationCreatedEvent.class));
    }
}
