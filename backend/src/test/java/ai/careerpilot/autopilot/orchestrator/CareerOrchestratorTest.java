package ai.careerpilot.autopilot.orchestrator;

import ai.careerpilot.autopilot.apply.AutoApplyEngine;
import ai.careerpilot.autopilot.decision.ApplicationDecisionEngine;
import ai.careerpilot.autopilot.decision.DecisionOutcome;
import ai.careerpilot.autopilot.orchestrator.CareerOrchestrator.AutopilotRunSummary;
import ai.careerpilot.autopilot.provider.SubmissionStatus;
import ai.careerpilot.autopilot.resume.AutopilotTailoringTrigger;
import ai.careerpilot.autopilot.resume.TailoringTriggerOutcome;
import ai.careerpilot.domain.ApplicationDecision;
import ai.careerpilot.domain.ApplicationSubmission;
import ai.careerpilot.domain.JobRecommendation;
import ai.careerpilot.repo.JobRecommendationRepository;
import ai.careerpilot.workflow.correlation.WorkflowCorrelationService;
import ai.careerpilot.workflow.correlation.WorkflowDeadLetterService;
import ai.careerpilot.workflow.entry.WorkflowEntryBridge;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CareerOrchestratorTest {

    private JobRecommendationRepository recommendations;
    private ApplicationDecisionEngine decisionEngine;
    private AutopilotTailoringTrigger tailoringTrigger;
    private AutoApplyEngine autoApplyEngine;
    private WorkflowCorrelationService correlation;
    private WorkflowDeadLetterService deadLetters;
    private WorkflowEntryBridge trackingEntry;
    private AutopilotMetrics metrics;
    private final UUID userId = UUID.randomUUID();
    private final UUID orgId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        recommendations = mock(JobRecommendationRepository.class);
        decisionEngine = mock(ApplicationDecisionEngine.class);
        tailoringTrigger = mock(AutopilotTailoringTrigger.class);
        autoApplyEngine = mock(AutoApplyEngine.class);
        correlation = mock(WorkflowCorrelationService.class);
        deadLetters = mock(WorkflowDeadLetterService.class);
        trackingEntry = mock(WorkflowEntryBridge.class);
        metrics = new AutopilotMetrics();
        when(correlation.start(any(), any(), any(), any())).thenReturn(UUID.randomUUID());
        when(tailoringTrigger.triggerIfNeeded(any(), any(), any())).thenReturn(TailoringTriggerOutcome.ALREADY_READY);
    }

    private CareerOrchestrator orchestrator(boolean enabled) {
        return new CareerOrchestrator(recommendations, decisionEngine, tailoringTrigger, autoApplyEngine,
                correlation, deadLetters, trackingEntry, metrics, enabled, 25);
    }

    private JobRecommendation rec(UUID jobId) {
        return JobRecommendation.builder().userId(userId).jobId(jobId).matchScore(90).build();
    }

    private void stubDecision(UUID jobId, DecisionOutcome outcome) {
        when(decisionEngine.decide(userId, jobId)).thenReturn(Optional.of(
                ApplicationDecision.builder().userId(userId).jobId(jobId).outcome(outcome.name()).build()));
    }

    @Test
    void disabledIsNoOp() {
        AutopilotRunSummary s = orchestrator(false).runForUser(userId, orgId);
        assertEquals(0, s.processed());
        verifyNoInteractions(recommendations, decisionEngine, autoApplyEngine);
    }

    @Test
    void talliesEachDecisionOutcome() {
        UUID j1 = UUID.randomUUID(), j2 = UUID.randomUUID(), j3 = UUID.randomUUID(), j4 = UUID.randomUUID();
        when(recommendations.findByUserIdOrderByMatchScoreDesc(userId))
                .thenReturn(List.of(rec(j1), rec(j2), rec(j3), rec(j4)));
        stubDecision(j1, DecisionOutcome.HUMAN_REVIEW);
        stubDecision(j2, DecisionOutcome.SAVE);
        stubDecision(j3, DecisionOutcome.IGNORE);
        stubDecision(j4, DecisionOutcome.HUMAN_REVIEW);

        AutopilotRunSummary s = orchestrator(true).runForUser(userId, orgId);
        assertEquals(4, s.processed());
        assertEquals(2, s.humanReview());
        assertEquals(1, s.saved());
        assertEquals(1, s.ignored());
        assertEquals(0, s.autoApplied());
    }

    @Test
    void autoApplyWithGenuineSubmissionStartsTracking() {
        UUID jobId = UUID.randomUUID();
        when(recommendations.findByUserIdOrderByMatchScoreDesc(userId)).thenReturn(List.of(rec(jobId)));
        stubDecision(jobId, DecisionOutcome.AUTO_APPLY);
        ApplicationSubmission sub = ApplicationSubmission.builder().id(UUID.randomUUID())
                .userId(userId).jobId(jobId).status(SubmissionStatus.SUBMITTED.name()).build();
        when(autoApplyEngine.apply(userId, jobId, null)).thenReturn(Optional.of(sub));

        AutopilotRunSummary s = orchestrator(true).runForUser(userId, orgId);
        assertEquals(1, s.autoApplied());
        verify(tailoringTrigger).triggerIfNeeded(userId, orgId, jobId);
        verify(trackingEntry).seed(eq(userId), eq(jobId), eq(sub.getId()), any(), any(), eq("autopilot"));
    }

    @Test
    void autoApplyRoutedToHumanReviewDoesNotStartTracking() {
        UUID jobId = UUID.randomUUID();
        when(recommendations.findByUserIdOrderByMatchScoreDesc(userId)).thenReturn(List.of(rec(jobId)));
        stubDecision(jobId, DecisionOutcome.AUTO_APPLY);
        ApplicationSubmission sub = ApplicationSubmission.builder().id(UUID.randomUUID())
                .userId(userId).jobId(jobId).status(SubmissionStatus.HUMAN_REVIEW.name()).build();
        when(autoApplyEngine.apply(userId, jobId, null)).thenReturn(Optional.of(sub));

        AutopilotRunSummary s = orchestrator(true).runForUser(userId, orgId);
        assertEquals(0, s.autoApplied());
        assertEquals(1, s.humanReview());
        verify(trackingEntry, never()).seed(any(), any(), any(), any(), any(), any());
    }

    @Test
    void decisionEngineDisabledSkipsWithoutProcessing() {
        UUID jobId = UUID.randomUUID();
        when(recommendations.findByUserIdOrderByMatchScoreDesc(userId)).thenReturn(List.of(rec(jobId)));
        when(decisionEngine.decide(userId, jobId)).thenReturn(Optional.empty());
        AutopilotRunSummary s = orchestrator(true).runForUser(userId, orgId);
        assertEquals(0, s.processed());
        verify(correlation).advance(any(), eq("DECISION"), eq("SKIPPED"));
    }

    @Test
    void perJobFailureIsDeadLetteredAndDoesNotAbortRun() {
        UUID j1 = UUID.randomUUID(), j2 = UUID.randomUUID();
        when(recommendations.findByUserIdOrderByMatchScoreDesc(userId)).thenReturn(List.of(rec(j1), rec(j2)));
        when(decisionEngine.decide(userId, j1)).thenThrow(new RuntimeException("boom"));
        stubDecision(j2, DecisionOutcome.SAVE);

        AutopilotRunSummary s = orchestrator(true).runForUser(userId, orgId);
        assertEquals(1, s.failed());
        assertEquals(1, s.saved());
        verify(deadLetters).record(any(), eq("career-orchestrator"), eq("RUN"), contains("job="), any(RuntimeException.class));
    }
}
