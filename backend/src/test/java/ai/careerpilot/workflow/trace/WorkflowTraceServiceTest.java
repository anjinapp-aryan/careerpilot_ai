package ai.careerpilot.workflow.trace;

import ai.careerpilot.domain.ApplicationLifecycle;
import ai.careerpilot.domain.WorkflowCorrelation;
import ai.careerpilot.domain.WorkflowDeadLetter;
import ai.careerpilot.repo.ApplicationAnalyticsRepository;
import ai.careerpilot.repo.ApplicationLifecycleRepository;
import ai.careerpilot.repo.ApplicationTimelineRepository;
import ai.careerpilot.repo.CareerIntelligenceRepository;
import ai.careerpilot.repo.InterviewRepository;
import ai.careerpilot.repo.WorkflowCorrelationRepository;
import ai.careerpilot.repo.WorkflowDeadLetterRepository;
import ai.careerpilot.workflow.trace.WorkflowTraceDtos.WorkflowStepDto;
import ai.careerpilot.workflow.trace.WorkflowTraceDtos.WorkflowTraceDto;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 3A follow-up — the read-only trace projection is deterministic and never infers a stage it
 * cannot see. These cover the documented workflow-status matrix (RUNNING/COMPLETED/FAILED/PARTIAL), the
 * step-status rules (SUCCESS/FAILED/PENDING/NOT_STARTED/SKIPPED), tenant isolation, and malformed input.
 */
class WorkflowTraceServiceTest {

    private final WorkflowCorrelationRepository correlations = mock(WorkflowCorrelationRepository.class);
    private final WorkflowDeadLetterRepository deadLetters = mock(WorkflowDeadLetterRepository.class);
    private final ApplicationLifecycleRepository lifecycles = mock(ApplicationLifecycleRepository.class);
    private final ApplicationTimelineRepository timelines = mock(ApplicationTimelineRepository.class);
    private final InterviewRepository interviews = mock(InterviewRepository.class);
    private final ApplicationAnalyticsRepository analytics = mock(ApplicationAnalyticsRepository.class);
    private final CareerIntelligenceRepository career = mock(CareerIntelligenceRepository.class);

    private final WorkflowTraceService service = new WorkflowTraceService(
            correlations, deadLetters, lifecycles, timelines, interviews, analytics, career);

    private final UUID userId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();
    private final UUID cid = UUID.randomUUID();

    private WorkflowCorrelation corr(String stage, String status) {
        return WorkflowCorrelation.builder()
                .id(UUID.randomUUID()).correlationId(cid).userId(userId).jobId(jobId)
                .workflowType("application-tracking").workflowStage(stage).status(status)
                .createdAt(Instant.parse("2026-07-05T10:00:00Z"))
                .updatedAt(Instant.parse("2026-07-05T10:15:00Z"))
                .build();
    }

    private void stubEmptyArtifacts() {
        when(lifecycles.findByUserIdAndJobId(any(), any())).thenReturn(Optional.empty());
        when(timelines.findByUserIdAndJobIdOrderByOccurredAtDesc(any(), any())).thenReturn(List.of());
        when(interviews.findByUserIdAndJobIdOrderByCreatedAtDesc(any(), any())).thenReturn(List.of());
        when(analytics.findByUserIdOrderByComputedAtDesc(any())).thenReturn(List.of());
        when(career.findByUserIdOrderByComputedAtDesc(any())).thenReturn(List.of());
        when(deadLetters.findByCorrelationIdOrderByCreatedAtDesc(any())).thenReturn(List.of());
    }

    private String stepStatus(WorkflowTraceDto t, WorkflowStage stage) {
        return t.steps().stream().filter(s -> s.step().equals(stage.dtoStep()))
                .map(WorkflowStepDto::status).findFirst().orElseThrow();
    }

    // ── not found / tenant / malformed ──

    @Test
    void missingCorrelationIsNotFound() {
        when(correlations.findByCorrelationId(cid)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getWorkflow(cid.toString(), userId))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void crossTenantIsNotFoundNotForbidden() {
        when(correlations.findByCorrelationId(cid)).thenReturn(Optional.of(corr("TRACKING", "IN_PROGRESS")));
        assertThatThrownBy(() -> service.getWorkflow(cid.toString(), UUID.randomUUID()))
                .isInstanceOf(NoSuchElementException.class); // never leaks existence via 403
    }

    @Test
    void malformedCorrelationIdIsBadRequest() {
        assertThatThrownBy(() -> service.getWorkflow("not-a-uuid", userId))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── completed ──

    @Test
    void completedWorkflowAllStepsSuccess() {
        when(correlations.findByCorrelationId(cid)).thenReturn(Optional.of(corr("CAREER_INTELLIGENCE", "COMPLETED")));
        stubEmptyArtifacts();
        when(lifecycles.findByUserIdAndJobId(userId, jobId)).thenReturn(Optional.of(
                ApplicationLifecycle.builder().currentStatus(ApplicationLifecycle.STATUS_ACCEPTED)
                        .createdAt(Instant.parse("2026-07-05T10:05:00Z")).build()));

        WorkflowTraceDto t = service.getWorkflow(cid.toString(), userId);
        assertThat(t.status()).isEqualTo("COMPLETED");
        assertThat(t.workflowType()).isEqualTo("APPLICATION_TRACKING");
        assertThat(t.steps()).allMatch(s -> s.status().equals("SUCCESS"));
        assertThat(t.completedAt()).isEqualTo(Instant.parse("2026-07-05T10:15:00Z"));
        assertThat(t.durationMs()).isEqualTo(900_000L);
        assertThat(t.deadLetters()).isEmpty();
    }

    // ── running / partial-progress / missing steps ──

    @Test
    void runningWorkflowMarksFrontierPendingAndRestNotStarted() {
        when(correlations.findByCorrelationId(cid)).thenReturn(Optional.of(corr("TIMELINE", "IN_PROGRESS")));
        stubEmptyArtifacts();

        WorkflowTraceDto t = service.getWorkflow(cid.toString(), userId);
        assertThat(t.status()).isEqualTo("RUNNING");
        assertThat(t.completedAt()).isNull();
        assertThat(t.durationMs()).isNull();
        // <= frontier (TIMELINE) succeeded
        assertThat(stepStatus(t, WorkflowStage.APPLICATION_CREATED)).isEqualTo("SUCCESS");
        assertThat(stepStatus(t, WorkflowStage.TIMELINE_UPDATED)).isEqualTo("SUCCESS");
        // frontier + 1 is the awaited step
        assertThat(stepStatus(t, WorkflowStage.EMAIL_CLASSIFIED)).isEqualTo("PENDING");
        // further out is untouched
        assertThat(stepStatus(t, WorkflowStage.INTERVIEW_DETECTED)).isEqualTo("NOT_STARTED");
        assertThat(stepStatus(t, WorkflowStage.CAREER_INTELLIGENCE)).isEqualTo("NOT_STARTED");
    }

    @Test
    void earlyWorkflowLeavesLaterStepsNotStarted() {
        when(correlations.findByCorrelationId(cid)).thenReturn(Optional.of(corr("TRACKING", "IN_PROGRESS")));
        stubEmptyArtifacts();
        WorkflowTraceDto t = service.getWorkflow(cid.toString(), userId);
        assertThat(stepStatus(t, WorkflowStage.STATUS_DETECTED)).isEqualTo("PENDING");
        assertThat(stepStatus(t, WorkflowStage.ANALYTICS_COMPUTED)).isEqualTo("NOT_STARTED");
    }

    // ── dead-letter / failed ──

    @Test
    void deadLetterOnRunningWorkflowIsFailedAndMarksThatStep() {
        when(correlations.findByCorrelationId(cid)).thenReturn(Optional.of(corr("INTERVIEW_DETECTION", "IN_PROGRESS")));
        stubEmptyArtifacts();
        when(deadLetters.findByCorrelationIdOrderByCreatedAtDesc(cid)).thenReturn(List.of(
                WorkflowDeadLetter.builder().correlationId(cid).workflow("interview-detection")
                        .stage("detect-interview").exception("boom")
                        .createdAt(Instant.parse("2026-07-05T10:08:00Z")).build()));

        WorkflowTraceDto t = service.getWorkflow(cid.toString(), userId);
        assertThat(t.status()).isEqualTo("FAILED");
        assertThat(stepStatus(t, WorkflowStage.INTERVIEW_DETECTED)).isEqualTo("FAILED");
        assertThat(t.deadLetters()).singleElement()
                .satisfies(d -> {
                    assertThat(d.worker()).isEqualTo("InterviewDetectionWorker");
                    assertThat(d.error()).isEqualTo("boom");
                    assertThat(d.timestamp()).isEqualTo(Instant.parse("2026-07-05T10:08:00Z"));
                });
    }

    @Test
    void completedWithDeadLetterIsPartial() {
        when(correlations.findByCorrelationId(cid)).thenReturn(Optional.of(corr("CAREER_INTELLIGENCE", "COMPLETED")));
        stubEmptyArtifacts();
        when(lifecycles.findByUserIdAndJobId(userId, jobId)).thenReturn(Optional.of(
                ApplicationLifecycle.builder().currentStatus(ApplicationLifecycle.STATUS_ACCEPTED).build()));
        when(deadLetters.findByCorrelationIdOrderByCreatedAtDesc(cid)).thenReturn(List.of(
                WorkflowDeadLetter.builder().correlationId(cid).workflow("application-timeline")
                        .stage("timeline").exception("transient").createdAt(Instant.now()).build()));

        WorkflowTraceDto t = service.getWorkflow(cid.toString(), userId);
        assertThat(t.status()).isEqualTo("PARTIAL");
        assertThat(stepStatus(t, WorkflowStage.TIMELINE_UPDATED)).isEqualTo("FAILED");
    }

    // ── offer branch: skipped when engine advanced past it without an offer outcome ──

    @Test
    void offerDetectionSkippedWhenNoOfferOutcome() {
        when(correlations.findByCorrelationId(cid)).thenReturn(Optional.of(corr("ANALYTICS", "IN_PROGRESS")));
        stubEmptyArtifacts();
        when(lifecycles.findByUserIdAndJobId(userId, jobId)).thenReturn(Optional.of(
                ApplicationLifecycle.builder().currentStatus(ApplicationLifecycle.STATUS_TECHNICAL_INTERVIEW).build()));

        WorkflowTraceDto t = service.getWorkflow(cid.toString(), userId);
        assertThat(stepStatus(t, WorkflowStage.OFFER_DETECTION)).isEqualTo("SKIPPED");
    }

    @Test
    void offerDetectionSuccessWhenOfferOutcomePresent() {
        when(correlations.findByCorrelationId(cid)).thenReturn(Optional.of(corr("ANALYTICS", "IN_PROGRESS")));
        stubEmptyArtifacts();
        when(lifecycles.findByUserIdAndJobId(userId, jobId)).thenReturn(Optional.of(
                ApplicationLifecycle.builder().currentStatus(ApplicationLifecycle.STATUS_OFFER_RECEIVED).build()));
        WorkflowTraceDto t = service.getWorkflow(cid.toString(), userId);
        assertThat(stepStatus(t, WorkflowStage.OFFER_DETECTION)).isEqualTo("SUCCESS");
    }

    // ── summary + diagnostics ──

    @Test
    void summarizeCountsStepsAndDeadLetters() {
        when(correlations.findByCorrelationId(cid)).thenReturn(Optional.of(corr("TIMELINE", "IN_PROGRESS")));
        stubEmptyArtifacts();
        var summary = service.summarize(cid.toString(), userId);
        assertThat(summary.totalSteps()).isEqualTo(10);
        assertThat(summary.completedSteps()).isEqualTo(4); // ENTRY, TRACKING, STATUS_DETECTED, TIMELINE
        assertThat(summary.failedSteps()).isZero();
        assertThat(summary.deadLetterCount()).isZero();
        assertThat(summary.status()).isEqualTo("RUNNING");
    }

    @Test
    void diagnosticsReportsExecutedStageCounts() {
        when(correlations.findByCorrelationId(cid)).thenReturn(Optional.of(corr("INTERVIEW_DETECTION", "IN_PROGRESS")));
        stubEmptyArtifacts();
        when(deadLetters.findByCorrelationIdOrderByCreatedAtDesc(cid)).thenReturn(List.of(
                WorkflowDeadLetter.builder().correlationId(cid).workflow("interview-detection")
                        .stage("detect-interview").exception("boom").createdAt(Instant.now()).build()));
        var diag = service.diagnostics(cid.toString(), userId);
        assertThat(diag.failedStages()).isEqualTo(1);
        assertThat(diag.completedStages()).isGreaterThan(0);
        assertThat(diag.totalEvents()).isEqualTo(diag.completedStages() + diag.failedStages());
        assertThat(diag.deadLetters()).isEqualTo(1);
    }
}
