package ai.careerpilot.story.api;

import ai.careerpilot.repo.StarStoryRepository;
import ai.careerpilot.repo.StoryRecommendationRepository;
import ai.careerpilot.repo.StoryUsageRepository;
import ai.careerpilot.repo.StoryVersionRepository;
import ai.careerpilot.story.config.StoryExecutorConfig;
import ai.careerpilot.story.metrics.StoryMetrics;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 7.15 — no-auth counts-only diagnostics for the STAR Story engine, same convention as every
 * other diagnostics controller: feature flags, executor queue depth, generated-story count, average
 * quality, categories/competencies distribution, recommendation count, failures. Path chosen not to
 * collide with any existing {@code /api/diagnostics/**} mapping (company/ats/workflow/ai/etc.).
 */
@RestController
@RequestMapping("/api/diagnostics/story")
public class StoryDiagnosticsController {

    private final StoryMetrics metrics;
    private final StarStoryRepository stories;
    private final StoryVersionRepository versions;
    private final StoryUsageRepository usage;
    private final StoryRecommendationRepository recommendations;
    private final ThreadPoolTaskExecutor executor;

    @Value("${story.engine.enabled:false}") private boolean engineEnabled;
    @Value("${story.extraction.enabled:false}") private boolean extractionEnabled;
    @Value("${story.generation.enabled:false}") private boolean generationEnabled;
    @Value("${story.recommendation.enabled:false}") private boolean recommendationEnabled;
    @Value("${story.analytics.enabled:false}") private boolean analyticsEnabled;
    @Value("${story.search.enabled:false}") private boolean searchEnabled;
    @Value("${story.history.enabled:false}") private boolean historyEnabled;
    @Value("${story.diagnostics.enabled:false}") private boolean diagnosticsEnabled;
    @Value("${story.worker.trigger.enabled:false}") private boolean workerTriggerEnabled;

    public StoryDiagnosticsController(StoryMetrics metrics, StarStoryRepository stories,
                                      StoryVersionRepository versions, StoryUsageRepository usage,
                                      StoryRecommendationRepository recommendations,
                                      @Qualifier(StoryExecutorConfig.STORY_EXECUTOR) ThreadPoolTaskExecutor executor) {
        this.metrics = metrics;
        this.stories = stories;
        this.versions = versions;
        this.usage = usage;
        this.recommendations = recommendations;
        this.executor = executor;
    }

    @GetMapping
    public Map<String, Object> overview() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", engineEnabled);
        out.put("extractionEnabled", extractionEnabled);
        out.put("generationEnabled", generationEnabled);
        out.put("recommendationEnabled", recommendationEnabled);
        out.put("analyticsEnabled", analyticsEnabled);
        out.put("searchEnabled", searchEnabled);
        out.put("historyEnabled", historyEnabled);
        out.put("diagnosticsEnabled", diagnosticsEnabled);
        out.put("workerTriggerEnabled", workerTriggerEnabled);

        long storyCount = safeCount(stories::count);
        out.put("storiesGenerated", storyCount);
        out.put("storyVersions", safeCount(versions::count));
        out.put("usageRecords", safeCount(usage::count));
        out.put("recommendationCount", safeCount(recommendations::count));
        out.put("averageQualityScore", averageQuality());
        out.put("categories", categories());

        int queueSize = executor.getThreadPoolExecutor().getQueue().size();
        out.put("executorActiveCount", executor.getActiveCount());
        out.put("executorPoolSize", executor.getPoolSize());
        out.put("executorQueueSize", queueSize);
        out.put("executorQueueCapacity", executor.getQueueCapacity());

        String health;
        if (!engineEnabled) {
            health = "NOT_CONFIGURED";
        } else if (queueSize >= executor.getQueueCapacity()) {
            health = "DOWN";
        } else if (metrics.get("generationFailures") > 0) {
            health = "DEGRADED";
        } else {
            health = "UP";
        }
        out.put("health", health);
        out.putAll(metrics.snapshot());
        return out;
    }

    private Double averageQuality() {
        try {
            List<Integer> scores = stories.findAll(PageRequest.of(0, 200)).stream()
                    .map(ai.careerpilot.domain.StarStory::getQualityScore)
                    .filter(java.util.Objects::nonNull)
                    .toList();
            return scores.isEmpty() ? null : scores.stream().mapToInt(Integer::intValue).average().orElse(0);
        } catch (Exception e) {
            return null;
        }
    }

    private List<String> categories() {
        try {
            return List.of(ai.careerpilot.story.StoryType.values()).stream().map(Enum::name).toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private static long safeCount(java.util.function.LongSupplier supplier) {
        try {
            return supplier.getAsLong();
        } catch (Exception e) {
            return -1;
        }
    }
}
