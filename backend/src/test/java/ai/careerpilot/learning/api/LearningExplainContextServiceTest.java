package ai.careerpilot.learning.api;

import ai.careerpilot.domain.RecommendationWeight;
import ai.careerpilot.domain.SuccessPattern;
import ai.careerpilot.learning.career.CareerLearningFacade;
import ai.careerpilot.learning.resume.AdaptiveResumeEngine;
import ai.careerpilot.repo.FailurePatternRepository;
import ai.careerpilot.repo.LearningEventRepository;
import ai.careerpilot.repo.RecommendationWeightRepository;
import ai.careerpilot.repo.SuccessPatternRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LearningExplainContextServiceTest {

    private LearningEventRepository events;
    private SuccessPatternRepository successPatterns;
    private FailurePatternRepository failurePatterns;
    private RecommendationWeightRepository weights;
    private AdaptiveResumeEngine resumeEngine;
    private CareerLearningFacade careerLearning;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        events = mock(LearningEventRepository.class);
        successPatterns = mock(SuccessPatternRepository.class);
        failurePatterns = mock(FailurePatternRepository.class);
        weights = mock(RecommendationWeightRepository.class);
        resumeEngine = mock(AdaptiveResumeEngine.class);
        careerLearning = mock(CareerLearningFacade.class);
    }

    @Test
    void disabledReturnsEmptySnapshotWithoutQuerying() {
        LearningExplainContextService svc = new LearningExplainContextService(
                events, successPatterns, failurePatterns, weights, resumeEngine, careerLearning, false);
        var snapshot = svc.get(userId);
        assertEquals(0, snapshot.totalEvents());
        assertTrue(snapshot.topSuccessPatterns().isEmpty());
        verifyNoInteractions(events, successPatterns, failurePatterns, weights, resumeEngine, careerLearning);
    }

    @Test
    void enabledAssemblesFromAllSources() {
        when(events.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(mock(ai.careerpilot.domain.LearningEvent.class)));
        SuccessPattern sp = SuccessPattern.builder().userId(userId).dimension(SuccessPattern.DIM_COMPANY).build();
        when(successPatterns.findByUserIdOrderBySuccessRateDesc(userId)).thenReturn(List.of(sp));
        when(failurePatterns.findByUserIdOrderByFailureRateDesc(userId)).thenReturn(List.of());
        RecommendationWeight rw = RecommendationWeight.builder().userId(userId).boost(10).build();
        when(weights.findByUserId(userId)).thenReturn(List.of(rw));
        when(resumeEngine.bestVersion(userId)).thenReturn(Optional.empty());
        when(careerLearning.strategy(userId)).thenReturn(Optional.empty());

        LearningExplainContextService svc = new LearningExplainContextService(
                events, successPatterns, failurePatterns, weights, resumeEngine, careerLearning, true);
        var snapshot = svc.get(userId);

        assertEquals(1, snapshot.totalEvents());
        assertEquals(1, snapshot.topSuccessPatterns().size());
        assertEquals(1, snapshot.topRecommendationWeights().size());
        assertNull(snapshot.bestResumeVersion());
        assertNull(snapshot.careerStrategy());
    }
}
