package ai.careerpilot.learning.career;

import ai.careerpilot.domain.LearningEvent;
import ai.careerpilot.domain.SuccessPattern;
import ai.careerpilot.learning.LearningEventType;
import ai.careerpilot.repo.LearningEventRepository;
import ai.careerpilot.repo.SuccessPatternRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CareerProbabilityEngineTest {

    private LearningEventRepository events;
    private SuccessPatternRepository successPatterns;
    private CareerProbabilityEngine engine;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        events = mock(LearningEventRepository.class);
        successPatterns = mock(SuccessPatternRepository.class);
        engine = new CareerProbabilityEngine(events, successPatterns);
        when(successPatterns.findByUserIdOrderBySuccessRateDesc(userId)).thenReturn(List.of());
    }

    private LearningEvent event(LearningEventType type, String company) {
        return LearningEvent.builder().eventType(type.name()).company(company).build();
    }

    @Test
    void noHistoryProducesAllZeros() {
        when(events.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of());
        var p = engine.compute(userId);
        assertEquals(0, BigDecimal.ZERO.compareTo(p.interviewProbability()));
        assertEquals(0, BigDecimal.ZERO.compareTo(p.offerProbability()));
        assertEquals(0, BigDecimal.ZERO.compareTo(p.careerSuccessProbability()));
        assertEquals(0, BigDecimal.ZERO.compareTo(p.marketDemandScore()));
    }

    @Test
    void interviewAndOfferProbabilityAreRatiosOverApplications() {
        when(events.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(
                event(LearningEventType.APPLICATION_SUBMITTED, "A"),
                event(LearningEventType.APPLICATION_SUBMITTED, "B"),
                event(LearningEventType.INTERVIEW_SCHEDULED, "A"),
                event(LearningEventType.OFFER_RECEIVED, "A")));

        var p = engine.compute(userId);
        assertEquals(0, new BigDecimal("0.5000").compareTo(p.interviewProbability()));
        assertEquals(0, new BigDecimal("0.5000").compareTo(p.offerProbability()));
    }

    @Test
    void careerGrowthIsAverageOfInterviewAndOfferProbability() {
        when(events.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(
                event(LearningEventType.APPLICATION_SUBMITTED, "A"),
                event(LearningEventType.INTERVIEW_SCHEDULED, "A"),
                event(LearningEventType.OFFER_RECEIVED, "A")));
        var p = engine.compute(userId);
        assertEquals(0, new BigDecimal("1.0000").compareTo(p.careerGrowthProbability()));
    }

    @Test
    void weightedSuccessRateAcrossPatterns() {
        SuccessPattern p1 = SuccessPattern.builder().applications(10).successRate(new BigDecimal("0.5000")).build();
        SuccessPattern p2 = SuccessPattern.builder().applications(30).successRate(new BigDecimal("0.1000")).build();
        when(successPatterns.findByUserIdOrderBySuccessRateDesc(userId)).thenReturn(List.of(p1, p2));
        when(events.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of());

        var p = engine.compute(userId);
        // (10*0.5 + 30*0.1) / 40 = (5+3)/40 = 0.2
        assertEquals(0, new BigDecimal("0.2000").compareTo(p.careerSuccessProbability()));
    }

    @Test
    void marketDemandIsShareOfCompaniesThatResponded() {
        when(events.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(
                event(LearningEventType.APPLICATION_SUBMITTED, "A"),
                event(LearningEventType.APPLICATION_SUBMITTED, "B"),
                event(LearningEventType.INTERVIEW_SCHEDULED, "A")));
        var p = engine.compute(userId);
        assertEquals(0, new BigDecimal("0.5000").compareTo(p.marketDemandScore()));
    }

    @Test
    void patternsWithNullSuccessRateAreIgnoredInWeightedAverage() {
        SuccessPattern p1 = SuccessPattern.builder().applications(5).successRate(null).build();
        when(successPatterns.findByUserIdOrderBySuccessRateDesc(userId)).thenReturn(List.of(p1));
        when(events.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of());
        var p = engine.compute(userId);
        assertEquals(0, BigDecimal.ZERO.compareTo(p.careerSuccessProbability()));
    }
}
