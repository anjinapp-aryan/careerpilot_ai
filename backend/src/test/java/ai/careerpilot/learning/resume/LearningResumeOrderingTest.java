package ai.careerpilot.learning.resume;

import ai.careerpilot.domain.RecommendationWeight;
import ai.careerpilot.learning.recommendation.AdaptiveRecommendationEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LearningResumeOrderingTest {

    private AdaptiveResumeEngine resumeEngine;
    private AdaptiveRecommendationEngine recommendationEngine;
    private LearningResumeOrdering ordering;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        resumeEngine = mock(AdaptiveResumeEngine.class);
        recommendationEngine = mock(AdaptiveRecommendationEngine.class);
        ordering = new LearningResumeOrdering(resumeEngine, recommendationEngine);
    }

    @Test
    void disabledReturnsInputUnchanged() {
        when(resumeEngine.isEnabled()).thenReturn(false);
        List<String> skills = List.of("java", "python");
        assertSame(skills, ordering.orderSkills(userId, skills));
        verifyNoInteractions(recommendationEngine);
    }

    @Test
    void nullOrSingleSkillListReturnedUnchanged() {
        when(resumeEngine.isEnabled()).thenReturn(true);
        assertNull(ordering.orderSkills(userId, null));
        List<String> one = List.of("java");
        assertSame(one, ordering.orderSkills(userId, one));
    }

    @Test
    void sortsByLearnedBoostDescending() {
        when(resumeEngine.isEnabled()).thenReturn(true);
        when(recommendationEngine.getBoost(userId, RecommendationWeight.DIM_SKILL, "java")).thenReturn(5);
        when(recommendationEngine.getBoost(userId, RecommendationWeight.DIM_SKILL, "python")).thenReturn(20);
        when(recommendationEngine.getBoost(userId, RecommendationWeight.DIM_SKILL, "go")).thenReturn(0);

        List<String> ordered = ordering.orderSkills(userId, List.of("java", "python", "go"));
        assertEquals(List.of("python", "java", "go"), ordered);
    }

    @Test
    void tiesPreserveOriginalOrder() {
        when(resumeEngine.isEnabled()).thenReturn(true);
        when(recommendationEngine.getBoost(any(), any(), any())).thenReturn(0);
        List<String> ordered = ordering.orderSkills(userId, List.of("go", "rust", "kotlin"));
        assertEquals(List.of("go", "rust", "kotlin"), ordered);
    }
}
