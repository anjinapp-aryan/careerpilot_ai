package ai.careerpilot.learning.career.goal;

import ai.careerpilot.domain.CareerStrategy;
import ai.careerpilot.repo.CareerStrategyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Phase 7.19 — verifies the co-writer upserts into the SAME CareerStrategy row, never a parallel table. */
class CareerGoalEngineTest {

    private final UUID userId = UUID.randomUUID();
    private SkillGapIntelligenceService skillGap;
    private PromotionReadinessService promotionReadiness;
    private CareerRoadmapGeneratorService roadmapGenerator;
    private CareerGoalPlannerService goalPlanner;
    private CareerStrategyRepository strategies;

    @BeforeEach
    void setUp() {
        skillGap = mock(SkillGapIntelligenceService.class);
        promotionReadiness = mock(PromotionReadinessService.class);
        roadmapGenerator = mock(CareerRoadmapGeneratorService.class);
        goalPlanner = mock(CareerGoalPlannerService.class);
        strategies = mock(CareerStrategyRepository.class);
        when(strategies.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private CareerGoalEngine engine() {
        return new CareerGoalEngine(skillGap, promotionReadiness, roadmapGenerator, goalPlanner, strategies);
    }

    @Test
    void recomputeSkipsSaveWhenAllThreeFlagsAreOff() {
        when(strategies.findByUserId(userId)).thenReturn(Optional.empty());
        when(skillGap.isEnabled()).thenReturn(false);
        when(promotionReadiness.isEnabled()).thenReturn(false);
        when(roadmapGenerator.isEnabled()).thenReturn(false);

        engine().recompute(userId);

        verify(strategies, never()).save(any());
    }

    @Test
    void recomputeUpsertsOntoExistingRowWhenSkillGapEnabled() {
        CareerStrategy existing = CareerStrategy.builder().id(UUID.randomUUID()).userId(userId).build();
        when(strategies.findByUserId(userId)).thenReturn(Optional.of(existing));
        when(skillGap.isEnabled()).thenReturn(true);
        when(skillGap.compute(userId)).thenReturn(Map.of("strengths", java.util.List.of("java")));
        when(promotionReadiness.isEnabled()).thenReturn(false);
        when(roadmapGenerator.isEnabled()).thenReturn(false);

        CareerStrategy result = engine().recompute(userId);

        assertThat(result.getId()).isEqualTo(existing.getId());
        assertThat(result.getSkillGapIntelligenceJson()).contains("java");
        assertThat(result.getCareerGoalComputedAt()).isNotNull();
    }

    @Test
    void planGoalPersistsOnlyWhenPlannerEnabledAndNoError() {
        when(goalPlanner.isEnabled()).thenReturn(true);
        when(goalPlanner.plan(userId, "Staff Engineer")).thenReturn(Map.of("targetLevel", "STAFF"));
        when(strategies.findByUserId(userId)).thenReturn(Optional.empty());

        engine().planGoal(userId, "Staff Engineer");

        verify(strategies).save(any());
    }

    @Test
    void planGoalNeverPersistsAnErrorResult() {
        when(goalPlanner.isEnabled()).thenReturn(true);
        when(goalPlanner.plan(userId, "bogus")).thenReturn(Map.of("error", "unrecognized goal"));

        engine().planGoal(userId, "bogus");

        verify(strategies, never()).save(any());
    }
}
