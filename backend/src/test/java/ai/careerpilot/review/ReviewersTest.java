package ai.careerpilot.review;

import ai.careerpilot.domain.JobRecommendation;
import ai.careerpilot.domain.ResumeAtsAnalysis;
import ai.careerpilot.domain.ResumeTailoring;
import ai.careerpilot.review.reviewer.*;
import ai.careerpilot.review.reviewer.ConsistencyReviewer.ConsistencyResult;
import ai.careerpilot.review.reviewer.QualityReviewer.QualityResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/** Pure reviewer scoring — deterministic, no mocks. Reviewers must degrade (not throw) on missing input. */
class ReviewersTest {

    // ── ResumeReviewer ──

    @Test
    void resumeReviewerHandlesMissingTailoring() {
        ReviewSection s = new ResumeReviewer(true).evaluate(null);
        assertEquals(0, s.score());
        assertFalse(s.reasons().isEmpty());
    }

    @Test
    void resumeReviewerScoresFromAtsAfter() {
        ResumeTailoring t = ResumeTailoring.builder().tailoringVersion(2).atsAfter(82)
                .improvementScore(10).confidenceScore(new BigDecimal("0.9")).build();
        ReviewSection s = new ResumeReviewer(true).evaluate(t);
        assertTrue(s.score() >= 82);
    }

    // ── AtsReviewer ──

    @Test
    void atsReviewerHandlesMissingAnalysis() {
        assertEquals(0, new AtsReviewer(true).evaluate(null).score());
    }

    @Test
    void atsReviewerReportsMissingKeywords() {
        ResumeAtsAnalysis a = ResumeAtsAnalysis.builder().atsScore(88)
                .missingKeywords("kafka, spark, k8s, terraform, go").build();
        ReviewSection s = new AtsReviewer(true).evaluate(a);
        assertTrue(s.score() <= 88);
        assertTrue(s.reasons().stream().anyMatch(r -> r.contains("missing keyword")));
    }

    // ── CompanyFitReviewer ──

    @Test
    void companyFitReviewerHandlesMissingRecommendation() {
        assertEquals(0, new CompanyFitReviewer(true).evaluate(null, false).score());
    }

    @Test
    void companyFitReviewerBoostsWithResearch() {
        JobRecommendation rec = new JobRecommendation();
        rec.setMatchScore(80);
        rec.setConfidenceLevel("HIGH");
        int withResearch = new CompanyFitReviewer(true).evaluate(rec, true).score();
        int without = new CompanyFitReviewer(true).evaluate(rec, false).score();
        assertTrue(withResearch >= without);
    }

    // ── LearningReviewer ──

    @Test
    void learningReviewerNeutralWithoutHistory() {
        ReviewSection s = new LearningReviewer(true).evaluate(
                new ai.careerpilot.learning.api.LearningExplainContextService.LearningExplainContext(
                        0, java.util.List.of(), java.util.List.of(), java.util.List.of(), null, null));
        assertEquals(50, s.score());
    }

    // ── ConsistencyReviewer ──

    @Test
    void consistencyFailsWithoutResumeOrRecommendation() {
        ConsistencyResult r = new ConsistencyReviewer(true).evaluate(false, false, false, true, true);
        assertEquals(ConsistencyStatus.FAIL, r.status());
    }

    @Test
    void consistencyWarnsWhenAtsWithoutTailoring() {
        ConsistencyResult r = new ConsistencyReviewer(true).evaluate(true, false, true, true, true);
        assertEquals(ConsistencyStatus.WARNING, r.status());
    }

    @Test
    void consistencyPassesWhenAllPresent() {
        ConsistencyResult r = new ConsistencyReviewer(true).evaluate(true, true, true, true, true);
        assertEquals(ConsistencyStatus.PASS, r.status());
    }

    // ── QualityReviewer ──

    @Test
    void qualityWeightedMeanAndBanding() {
        QualityResult q = new QualityReviewer(true).evaluate(90, 90, 90, 90, ConsistencyStatus.PASS);
        assertEquals(90, q.score());
        assertEquals(QualityCategory.EXCELLENT, q.category());
    }

    @Test
    void qualityBlockedOnConsistencyFail() {
        QualityResult q = new QualityReviewer(true).evaluate(95, 95, 95, 95, ConsistencyStatus.FAIL);
        assertEquals(QualityCategory.BLOCKED, q.category());
    }

    @Test
    void qualityIgnoresAbsentReviewers() {
        // only resume present → score equals resume score.
        QualityResult q = new QualityReviewer(true).evaluate(70, null, null, null, ConsistencyStatus.PASS);
        assertEquals(70, q.score());
        assertEquals(QualityCategory.GOOD, q.category());
    }
}
