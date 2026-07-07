package ai.careerpilot.learning.pattern;

import ai.careerpilot.domain.LearningEvent;
import ai.careerpilot.learning.LearningEventType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PatternStatsCalculatorTest {

    private static LearningEvent of(LearningEventType type) {
        return LearningEvent.builder().eventType(type.name()).build();
    }

    @Test
    void successWithNoApplicationsIsZeroed() {
        var stats = PatternStatsCalculator.success(List.of());
        assertEquals(0, stats.applications());
        assertNull(stats.successRate());
    }

    @Test
    void successRateIsOffersOverApplications() {
        var events = List.of(
                of(LearningEventType.APPLICATION_SUBMITTED), of(LearningEventType.APPLICATION_SUBMITTED),
                of(LearningEventType.INTERVIEW_SCHEDULED), of(LearningEventType.OFFER_RECEIVED));
        var stats = PatternStatsCalculator.success(events);
        assertEquals(2, stats.applications());
        assertEquals(1, stats.interviews());
        assertEquals(1, stats.offers());
        assertEquals(0, new BigDecimal("0.5000").compareTo(stats.successRate()));
    }

    @Test
    void failureWithNoApplicationsHasNullRate() {
        var stats = PatternStatsCalculator.failure(List.of());
        assertEquals(0, stats.applications());
        assertNull(stats.failureRate());
        assertNull(stats.recommendedPenalty());
    }

    @Test
    void failureRateIsInverseOfResponseRate() {
        var events = List.of(
                of(LearningEventType.APPLICATION_SUBMITTED), of(LearningEventType.APPLICATION_SUBMITTED),
                of(LearningEventType.APPLICATION_SUBMITTED), of(LearningEventType.APPLICATION_SUBMITTED),
                of(LearningEventType.INTERVIEW_SCHEDULED));
        var stats = PatternStatsCalculator.failure(events);
        assertEquals(4, stats.applications());
        assertEquals(1, stats.responses());
        assertEquals(0, new BigDecimal("0.7500").compareTo(stats.failureRate()));
    }

    @Test
    void totalSilenceProducesMaxPenalty() {
        var events = List.of(of(LearningEventType.APPLICATION_SUBMITTED), of(LearningEventType.APPLICATION_SUBMITTED));
        var stats = PatternStatsCalculator.failure(events);
        assertEquals(0, stats.responses());
        assertEquals(-30, stats.recommendedPenalty());
    }

    @Test
    void rejectionCountsAsAResponseNotJustSilence() {
        var events = List.of(of(LearningEventType.APPLICATION_SUBMITTED), of(LearningEventType.APPLICATION_REJECTED));
        var stats = PatternStatsCalculator.failure(events);
        assertEquals(1, stats.responses());
    }

    @Test
    void fullSuccessProducesZeroFailurePenalty() {
        var events = List.of(of(LearningEventType.APPLICATION_SUBMITTED), of(LearningEventType.OFFER_RECEIVED));
        var stats = PatternStatsCalculator.failure(events);
        assertEquals(0, stats.recommendedPenalty());
    }

    @Test
    void unrelatedEventTypesAreIgnored() {
        var events = List.of(of(LearningEventType.RESUME_SELECTED), of(LearningEventType.WORKFLOW_COMPLETED));
        var stats = PatternStatsCalculator.success(events);
        assertEquals(0, stats.applications());
        assertEquals(0, stats.interviews());
        assertEquals(0, stats.offers());
    }
}
