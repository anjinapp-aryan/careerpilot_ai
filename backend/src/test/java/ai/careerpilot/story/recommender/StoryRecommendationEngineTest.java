package ai.careerpilot.story.recommender;

import ai.careerpilot.domain.StarStory;
import ai.careerpilot.domain.StoryRecommendation;
import ai.careerpilot.repo.CompanyKnowledgeRepository;
import ai.careerpilot.repo.StarStoryRepository;
import ai.careerpilot.repo.StoryRecommendationRepository;
import ai.careerpilot.story.StorySource;
import ai.careerpilot.story.StoryStatus;
import ai.careerpilot.story.StoryType;
import ai.careerpilot.story.metrics.StoryMetrics;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StoryRecommendationEngineTest {

    private final StarStoryRepository stories = mock(StarStoryRepository.class);
    private final StoryRecommendationRepository recommendations = mock(StoryRecommendationRepository.class);
    private final CompanyKnowledgeRepository companies = mock(CompanyKnowledgeRepository.class);
    private final StoryMetrics metrics = new StoryMetrics();
    private final UUID userId = UUID.randomUUID();

    private StarStory story(StoryType type, Integer quality) {
        return StarStory.builder().id(UUID.randomUUID()).userId(userId).title("Story " + type)
                .storyType(type).status(StoryStatus.COMPLETE).source(StorySource.MANUAL)
                .qualityScore(quality).currentVersion(1).build();
    }

    @Test
    void disabledReturnsEmptyList() {
        StoryRecommendationEngine engine = new StoryRecommendationEngine(stories, recommendations, companies, metrics, false);
        assertTrue(engine.recommend(userId, "Acme", "Engineer", "Tell me about a conflict", 5).isEmpty());
    }

    @Test
    void noStoriesReturnsEmptyList() {
        when(stories.findByUserIdOrderByUpdatedAtDesc(userId)).thenReturn(List.of());
        StoryRecommendationEngine engine = new StoryRecommendationEngine(stories, recommendations, companies, metrics, true);
        assertTrue(engine.recommend(userId, "Acme", "Engineer", "question", 5).isEmpty());
    }

    @Test
    void questionHintingConflictPrefersConflictResolutionStory() {
        StarStory conflict = story(StoryType.CONFLICT_RESOLUTION, 70);
        StarStory success = story(StoryType.SUCCESS, 70);
        when(stories.findByUserIdOrderByUpdatedAtDesc(userId)).thenReturn(List.of(conflict, success));
        when(recommendations.save(any())).thenAnswer(inv -> inv.getArgument(0));

        StoryRecommendationEngine engine = new StoryRecommendationEngine(stories, recommendations, companies, metrics, true);
        List<StoryRecommendation> results = engine.recommend(userId, null, null, "Tell me about a conflict you resolved", 5);
        assertFalse(results.isEmpty());
        assertEquals(conflict.getId(), results.get(0).getStarStoryId());
    }

    @Test
    void higherQualityStoryRanksHigherWhenNoQuestion() {
        StarStory low = story(StoryType.SUCCESS, 20);
        StarStory high = story(StoryType.SUCCESS, 90);
        when(stories.findByUserIdOrderByUpdatedAtDesc(userId)).thenReturn(List.of(low, high));
        when(recommendations.save(any())).thenAnswer(inv -> inv.getArgument(0));

        StoryRecommendationEngine engine = new StoryRecommendationEngine(stories, recommendations, companies, metrics, true);
        List<StoryRecommendation> results = engine.recommend(userId, null, null, null, 5);
        assertEquals(high.getId(), results.get(0).getStarStoryId());
    }

    @Test
    void limitIsRespected() {
        List<StarStory> many = List.of(story(StoryType.SUCCESS, 10), story(StoryType.SUCCESS, 20),
                story(StoryType.SUCCESS, 30));
        when(stories.findByUserIdOrderByUpdatedAtDesc(userId)).thenReturn(many);
        when(recommendations.save(any())).thenAnswer(inv -> inv.getArgument(0));

        StoryRecommendationEngine engine = new StoryRecommendationEngine(stories, recommendations, companies, metrics, true);
        assertTrue(engine.recommend(userId, null, null, null, 2).size() <= 2);
    }

    @Test
    void companySignalsAreALookupNoOpWhenCompanyUnknown() {
        when(companies.findByUserIdAndNormalizedName(any(), any())).thenReturn(Optional.empty());
        when(stories.findByUserIdOrderByUpdatedAtDesc(userId)).thenReturn(List.of(story(StoryType.SUCCESS, 50)));
        when(recommendations.save(any())).thenAnswer(inv -> inv.getArgument(0));

        StoryRecommendationEngine engine = new StoryRecommendationEngine(stories, recommendations, companies, metrics, true);
        assertFalse(engine.recommend(userId, "Unknown Co", null, null, 5).isEmpty());
    }
}
