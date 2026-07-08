package ai.careerpilot.learning.recommendation;

import ai.careerpilot.domain.CareerStrategy;
import ai.careerpilot.domain.Job;
import ai.careerpilot.domain.RecommendationWeight;
import ai.careerpilot.domain.ResumeLearning;
import ai.careerpilot.learning.resume.AdaptiveResumeEngine;
import ai.careerpilot.repo.CareerStrategyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LearningRecommendationBoosterTest {

    private AdaptiveRecommendationEngine recommendationEngine;
    private AdaptiveResumeEngine resumeEngine;
    private CareerStrategyRepository careerStrategies;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        recommendationEngine = mock(AdaptiveRecommendationEngine.class);
        resumeEngine = mock(AdaptiveResumeEngine.class);
        careerStrategies = mock(CareerStrategyRepository.class);
    }

    private Job job(String company, String jobFamily, String country) {
        Job j = new Job();
        j.setCompany(company);
        j.setJobFamily(jobFamily);
        j.setCountry(country);
        return j;
    }

    @Test
    void everyFlagOffIsInactiveAndReturnsZero() {
        when(recommendationEngine.isEnabled()).thenReturn(false);
        when(resumeEngine.isEnabled()).thenReturn(false);
        LearningRecommendationBooster booster = new LearningRecommendationBooster(
                recommendationEngine, resumeEngine, careerStrategies, false);

        assertFalse(booster.isActive());
        assertEquals(0, booster.computeBoost(userId, job("JP Morgan", "ENGINEERING", "US"), List.of("java")));
        verifyNoInteractions(careerStrategies);
    }

    @Test
    void sumsCompanyRoleIndustryLocationAndSkillBoosts() {
        when(recommendationEngine.isEnabled()).thenReturn(true);
        when(resumeEngine.isEnabled()).thenReturn(false);
        Job j = job("JP Morgan", "ENGINEERING", "US");
        when(recommendationEngine.getBoost(userId, RecommendationWeight.DIM_COMPANY, "JP Morgan")).thenReturn(25);
        when(recommendationEngine.getBoost(userId, RecommendationWeight.DIM_ROLE, "ENGINEERING")).thenReturn(15);
        when(recommendationEngine.getBoost(userId, RecommendationWeight.DIM_INDUSTRY, "ENGINEERING")).thenReturn(5);
        when(recommendationEngine.getBoost(eq(userId), eq(RecommendationWeight.DIM_SKILL), any())).thenReturn(2);

        LearningRecommendationBooster booster = new LearningRecommendationBooster(
                recommendationEngine, resumeEngine, careerStrategies, false);

        int boost = booster.computeBoost(userId, j, List.of("java", "spring"));
        assertEquals(25 + 15 + 5 + 2 + 2, boost);
    }

    @Test
    void resumeBoostScaledFromOfferRateWhenEnabled() {
        when(recommendationEngine.isEnabled()).thenReturn(false);
        when(resumeEngine.isEnabled()).thenReturn(true);
        ResumeLearning best = ResumeLearning.builder().offerRate(new BigDecimal("0.5")).build();
        when(resumeEngine.bestVersion(userId)).thenReturn(Optional.of(best));

        LearningRecommendationBooster booster = new LearningRecommendationBooster(
                recommendationEngine, resumeEngine, careerStrategies, false);

        assertEquals(10, booster.computeBoost(userId, job("X", "Y", "Z"), List.of()));
    }

    @Test
    void careerBoostScaledFromSuccessProbabilityWhenEnabled() {
        when(recommendationEngine.isEnabled()).thenReturn(false);
        when(resumeEngine.isEnabled()).thenReturn(false);
        CareerStrategy strategy = CareerStrategy.builder()
                .careerSuccessProbability(BigDecimal.ONE.setScale(4, RoundingMode.HALF_UP)).build();
        when(careerStrategies.findByUserId(userId)).thenReturn(Optional.of(strategy));

        LearningRecommendationBooster booster = new LearningRecommendationBooster(
                recommendationEngine, resumeEngine, careerStrategies, true);

        assertEquals(15, booster.computeBoost(userId, job("X", "Y", "Z"), List.of()));
        assertTrue(booster.isActive());
    }

    @Test
    void nullDimensionValuesContributeNothing() {
        when(recommendationEngine.isEnabled()).thenReturn(true);
        when(resumeEngine.isEnabled()).thenReturn(false);
        Job j = job(null, null, null); // no salary either
        LearningRecommendationBooster booster = new LearningRecommendationBooster(
                recommendationEngine, resumeEngine, careerStrategies, false);

        assertEquals(0, booster.computeBoost(userId, j, List.of()));
    }
}
