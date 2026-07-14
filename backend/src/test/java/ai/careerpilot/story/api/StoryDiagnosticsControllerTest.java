package ai.careerpilot.story.api;

import ai.careerpilot.repo.StarStoryRepository;
import ai.careerpilot.repo.StoryRecommendationRepository;
import ai.careerpilot.repo.StoryUsageRepository;
import ai.careerpilot.repo.StoryVersionRepository;
import ai.careerpilot.story.metrics.StoryMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StoryDiagnosticsControllerTest {

    private final StoryMetrics metrics = new StoryMetrics();
    private final StarStoryRepository stories = mock(StarStoryRepository.class);
    private final StoryVersionRepository versions = mock(StoryVersionRepository.class);
    private final StoryUsageRepository usage = mock(StoryUsageRepository.class);
    private final StoryRecommendationRepository recommendations = mock(StoryRecommendationRepository.class);
    private final ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);

    private StoryDiagnosticsController controller() {
        ThreadPoolExecutor jdkExecutor = mock(ThreadPoolExecutor.class);
        when(executor.getThreadPoolExecutor()).thenReturn(jdkExecutor);
        when(jdkExecutor.getQueue()).thenReturn(new java.util.concurrent.LinkedBlockingQueue<>());
        when(executor.getQueueCapacity()).thenReturn(200);
        when(stories.findAll(any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        return new StoryDiagnosticsController(metrics, stories, versions, usage, recommendations, executor);
    }

    @Test
    void reportsNotConfiguredWhenEngineDisabled() {
        Map<String, Object> result = controller().overview();
        assertEquals("NOT_CONFIGURED", result.get("health"));
        assertEquals(false, result.get("enabled"));
    }

    @Test
    void overviewIncludesAllFlagKeys() {
        Map<String, Object> result = controller().overview();
        for (String key : new String[]{"extractionEnabled", "generationEnabled", "recommendationEnabled",
                "analyticsEnabled", "searchEnabled", "historyEnabled", "diagnosticsEnabled", "workerTriggerEnabled"}) {
            assertTrue(result.containsKey(key), "missing " + key);
        }
    }

    @Test
    void safeCountReturnsNegativeOneOnRepositoryFailure() {
        when(stories.count()).thenThrow(new RuntimeException("table missing"));
        Map<String, Object> result = controller().overview();
        assertEquals(-1L, result.get("storiesGenerated"));
    }

    @Test
    void overviewIncludesCategoriesList() {
        Map<String, Object> result = controller().overview();
        @SuppressWarnings("unchecked")
        List<String> categories = (List<String>) result.get("categories");
        assertEquals(28, categories.size());
    }
}
