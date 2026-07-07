package ai.careerpilot.learning.recommendation;

import ai.careerpilot.domain.FailurePattern;
import ai.careerpilot.domain.RecommendationWeight;
import ai.careerpilot.domain.SuccessPattern;
import ai.careerpilot.repo.FailurePatternRepository;
import ai.careerpilot.repo.SuccessPatternRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RecommendationLearningServiceTest {

    private SuccessPatternRepository successPatterns;
    private FailurePatternRepository failurePatterns;
    private RecommendationWeightManager weightManager;
    private RecommendationLearningService service;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        successPatterns = mock(SuccessPatternRepository.class);
        failurePatterns = mock(FailurePatternRepository.class);
        weightManager = mock(RecommendationWeightManager.class);
        service = new RecommendationLearningService(successPatterns, failurePatterns, weightManager);
        // Default: no patterns for any dimension unless overridden.
        when(successPatterns.findByUserIdAndDimensionOrderBySuccessRateDesc(any(), any())).thenReturn(List.of());
        when(failurePatterns.findByUserIdAndDimensionOrderByFailureRateDesc(any(), any())).thenReturn(List.of());
    }

    @Test
    void successOnlyPatternProducesPositiveBoost() {
        SuccessPattern sp = SuccessPattern.builder().userId(userId).dimension(SuccessPattern.DIM_COMPANY)
                .dimensionKey("JP Morgan").applications(50).offers(5).successRate(new BigDecimal("0.5000")).build();
        when(successPatterns.findByUserIdAndDimensionOrderBySuccessRateDesc(userId, SuccessPattern.DIM_COMPANY))
                .thenReturn(List.of(sp));

        service.recompute(userId);

        verify(weightManager).upsert(eq(userId), eq(RecommendationWeight.DIM_COMPANY), eq("JP Morgan"), eq(15), anyString());
    }

    @Test
    void failureOnlyPatternProducesPurePenalty() {
        FailurePattern fp = FailurePattern.builder().userId(userId).dimension(FailurePattern.DIM_LOCATION)
                .dimensionKey("Germany").applications(30).responses(0).recommendedPenalty(-30).build();
        when(failurePatterns.findByUserIdAndDimensionOrderByFailureRateDesc(userId, FailurePattern.DIM_LOCATION))
                .thenReturn(List.of(fp));

        service.recompute(userId);

        verify(weightManager).upsert(eq(userId), eq(RecommendationWeight.DIM_LOCATION), eq("Germany"), eq(-30), anyString());
    }

    @Test
    void combinedSuccessAndFailureNetOut() {
        SuccessPattern sp = SuccessPattern.builder().userId(userId).dimension(SuccessPattern.DIM_SKILL)
                .dimensionKey("Java").applications(10).successRate(new BigDecimal("1.0000")).build();
        FailurePattern fp = FailurePattern.builder().userId(userId).dimension(FailurePattern.DIM_SKILL)
                .dimensionKey("Java").applications(10).recommendedPenalty(-10).build();
        when(successPatterns.findByUserIdAndDimensionOrderBySuccessRateDesc(userId, SuccessPattern.DIM_SKILL))
                .thenReturn(List.of(sp));
        when(failurePatterns.findByUserIdAndDimensionOrderByFailureRateDesc(userId, FailurePattern.DIM_SKILL))
                .thenReturn(List.of(fp));

        service.recompute(userId);

        // successBoost = round(1.0 * 30) = 30; penalty = -10; net = 20
        verify(weightManager).upsert(eq(userId), eq(RecommendationWeight.DIM_SKILL), eq("Java"), eq(20), anyString());
    }

    @Test
    void noPatternsProducesNoUpserts() {
        service.recompute(userId);
        verifyNoInteractions(weightManager);
    }

    @Test
    void resumeDimensionIsNeverPassedToWeightManager() {
        SuccessPattern sp = SuccessPattern.builder().userId(userId).dimension(SuccessPattern.DIM_RESUME)
                .dimensionKey("v1").applications(5).successRate(new BigDecimal("0.2000")).build();
        when(successPatterns.findByUserIdAndDimensionOrderBySuccessRateDesc(userId, SuccessPattern.DIM_RESUME))
                .thenReturn(List.of(sp));
        service.recompute(userId);
        verify(weightManager, never()).upsert(any(), eq("RESUME"), any(), anyInt(), any());
    }
}
