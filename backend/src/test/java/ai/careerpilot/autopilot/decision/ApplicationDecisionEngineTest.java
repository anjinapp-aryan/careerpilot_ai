package ai.careerpilot.autopilot.decision;

import ai.careerpilot.autopilot.decision.ApplicationDecisionEngine.DecisionResult;
import ai.careerpilot.autopilot.decision.ApplicationDecisionEngine.DecisionSignals;
import ai.careerpilot.domain.JobRecommendation;
import ai.careerpilot.jobdiscovery.JobCategory;
import ai.careerpilot.repo.ApplicationDecisionRepository;
import ai.careerpilot.repo.JobRecommendationRepository;
import ai.careerpilot.repo.ResumeAtsAnalysisRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ApplicationDecisionEngineTest {

    private JobRecommendationRepository recommendations;
    private ResumeAtsAnalysisRepository atsAnalyses;
    private ApplicationDecisionRepository decisions;
    private final UUID userId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        recommendations = mock(JobRecommendationRepository.class);
        atsAnalyses = mock(ResumeAtsAnalysisRepository.class);
        decisions = mock(ApplicationDecisionRepository.class);
        when(atsAnalyses.findFirstByUserIdAndJobIdOrderByCreatedAtDesc(any(), any())).thenReturn(Optional.empty());
        when(decisions.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private ApplicationDecisionEngine engine(boolean enabled) {
        return new ApplicationDecisionEngine(recommendations, atsAnalyses, decisions,
                enabled, 90, 75, 60, 70, -20);
    }

    // ── pure evaluate() rules ──

    @Test
    void archivedCategoryIsIgnored() {
        DecisionResult r = engine(true).evaluate(new DecisionSignals(99, true, 90, 10, JobCategory.ARCHIVED.name(), "HIGH"));
        assertEquals(DecisionOutcome.IGNORE, r.outcome());
    }

    @Test
    void belowSaveFloorIsIgnored() {
        assertEquals(DecisionOutcome.IGNORE,
                engine(true).evaluate(new DecisionSignals(55, true, 90, 0, null, null)).outcome());
    }

    @Test
    void strongMustApplyWithGoodAtsAndPositiveLearningIsAutoApply() {
        DecisionResult r = engine(true).evaluate(new DecisionSignals(92, true, 85, 15, JobCategory.HIGH_PRIORITY.name(), "HIGH"));
        assertEquals(DecisionOutcome.AUTO_APPLY, r.outcome());
    }

    @Test
    void autoApplyScoreButNotMustApplyDegradesToReview() {
        assertEquals(DecisionOutcome.HUMAN_REVIEW,
                engine(true).evaluate(new DecisionSignals(95, false, 90, 5, null, null)).outcome());
    }

    @Test
    void autoApplyScoreButLowAtsDegradesToReview() {
        assertEquals(DecisionOutcome.HUMAN_REVIEW,
                engine(true).evaluate(new DecisionSignals(95, true, 50, 5, null, null)).outcome());
    }

    @Test
    void strongFailureHistoryNeverAutoApplies() {
        assertEquals(DecisionOutcome.HUMAN_REVIEW,
                engine(true).evaluate(new DecisionSignals(98, true, 95, -25, null, null)).outcome());
    }

    @Test
    void negativeLearningBelowCapButNotStrongStillBlocksAutoApply() {
        // learningBoost -5 (not <= -20) but < 0 → must-apply auto-apply path requires >= 0, so review.
        assertEquals(DecisionOutcome.HUMAN_REVIEW,
                engine(true).evaluate(new DecisionSignals(95, true, 90, -5, null, null)).outcome());
    }

    @Test
    void midScoreIsSave() {
        assertEquals(DecisionOutcome.SAVE,
                engine(true).evaluate(new DecisionSignals(65, true, 90, 0, null, null)).outcome());
    }

    @Test
    void unknownAtsIsTreatedAsAcceptableForAutoApply() {
        assertEquals(DecisionOutcome.AUTO_APPLY,
                engine(true).evaluate(new DecisionSignals(91, true, null, 0, null, null)).outcome());
    }

    // ── decide() wiring ──

    @Test
    void disabledEngineIsNoOp() {
        assertTrue(engine(false).decide(userId, jobId).isEmpty());
        verifyNoInteractions(recommendations, decisions);
    }

    @Test
    void noRecommendationYieldsEmpty() {
        when(recommendations.findByUserIdAndJobId(userId, jobId)).thenReturn(Optional.empty());
        assertTrue(engine(true).decide(userId, jobId).isEmpty());
        verify(decisions, never()).save(any());
    }

    @Test
    void decidePersistsDecisionWithParsedLearningBoost() {
        JobRecommendation rec = JobRecommendation.builder()
                .userId(userId).jobId(jobId).matchScore(92).mustApply(true)
                .category(JobCategory.HIGH_PRIORITY.name()).priority("HIGH")
                .scoreBreakdown("{\"skills\":90,\"learningBoost\":12}")
                .build();
        when(recommendations.findByUserIdAndJobId(userId, jobId)).thenReturn(Optional.of(rec));

        var out = engine(true).decide(userId, jobId);
        assertTrue(out.isPresent());
        assertEquals(DecisionOutcome.AUTO_APPLY.name(), out.get().getOutcome());
        assertEquals(12, out.get().getLearningBoost());
        assertEquals(92, out.get().getMatchScore());
    }

    @Test
    void malformedBreakdownJsonTreatedAsZeroBoost() {
        JobRecommendation rec = JobRecommendation.builder()
                .userId(userId).jobId(jobId).matchScore(80).mustApply(false)
                .scoreBreakdown("not json").build();
        when(recommendations.findByUserIdAndJobId(userId, jobId)).thenReturn(Optional.of(rec));
        var out = engine(true).decide(userId, jobId);
        assertTrue(out.isPresent());
        assertEquals(0, out.get().getLearningBoost());
        assertEquals(DecisionOutcome.HUMAN_REVIEW.name(), out.get().getOutcome());
    }
}
