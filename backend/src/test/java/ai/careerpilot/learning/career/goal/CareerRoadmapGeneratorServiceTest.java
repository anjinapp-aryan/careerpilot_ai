package ai.careerpilot.learning.career.goal;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CareerRoadmapGeneratorServiceTest {

    private final UUID userId = UUID.randomUUID();
    private final SkillGapIntelligenceService skillGap = mock(SkillGapIntelligenceService.class);
    private final PromotionReadinessService promotionReadiness = mock(PromotionReadinessService.class);

    private CareerRoadmapGeneratorService service(boolean enabled) {
        return new CareerRoadmapGeneratorService(skillGap, promotionReadiness, enabled);
    }

    @Test
    void disabledReturnsEmptyMap() {
        assertThat(service(false).generate(userId)).isEmpty();
    }

    @Test
    void omittedSectionsAreExplicitlyListedNeverFabricated() {
        when(skillGap.compute(any())).thenReturn(Map.of());
        when(promotionReadiness.compute(any())).thenReturn(Map.of());

        Map<String, Object> result = service(true).generate(userId);

        assertThat(result.get("omittedSections")).isEqualTo(
                List.of("openSource", "networking", "resumeImprovements", "salaryPreparation", "offerStrategy"));
    }

    @Test
    void allFiveHorizonsArePresent() {
        when(skillGap.compute(any())).thenReturn(Map.of());
        when(promotionReadiness.compute(any())).thenReturn(Map.of());

        Map<String, Object> result = service(true).generate(userId);

        assertThat(result).containsKeys("today", "nextMonth", "threeMonths", "sixMonths", "twelveMonths");
    }

    @Test
    void nextMonthReferencesRealLearningPrioritySuggestions() {
        when(skillGap.compute(any())).thenReturn(Map.of(
                "learningPriority", List.of("kubernetes"),
                "suggestions", Map.of("kubernetes", Map.of("project", "Deploy a multi-service app on a local k8s cluster",
                        "certification", "CKA", "practice", "Practice Helm charts"))));
        when(promotionReadiness.compute(any())).thenReturn(Map.of());

        Map<String, Object> result = service(true).generate(userId);

        @SuppressWarnings("unchecked")
        List<String> nextMonth = (List<String>) result.get("nextMonth");
        assertThat(nextMonth).anyMatch(s -> s.contains("kubernetes"));
    }
}
