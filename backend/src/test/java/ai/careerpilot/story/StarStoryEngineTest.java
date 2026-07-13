package ai.careerpilot.story;

import ai.careerpilot.domain.StarStory;
import ai.careerpilot.domain.StoryAnalytics;
import ai.careerpilot.repo.StarStoryRepository;
import ai.careerpilot.repo.StoryAnalyticsRepository;
import ai.careerpilot.repo.StoryUsageRepository;
import ai.careerpilot.story.analyzer.BehavioralAnalyzer;
import ai.careerpilot.story.engine.StoryVersionManager;
import ai.careerpilot.story.evaluator.StoryQualityEvaluator;
import ai.careerpilot.story.extractor.StoryExtractionEngine;
import ai.careerpilot.story.generator.StoryGenerationEngine;
import ai.careerpilot.story.generator.StoryGenerationEngine.Draft;
import ai.careerpilot.story.metrics.StoryMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class StarStoryEngineTest {

    private final StarStoryRepository stories = mock(StarStoryRepository.class);
    private final StoryUsageRepository usage = mock(StoryUsageRepository.class);
    private final StoryAnalyticsRepository analytics = mock(StoryAnalyticsRepository.class);
    private final StoryExtractionEngine extraction = mock(StoryExtractionEngine.class);
    private final StoryGenerationEngine generation = mock(StoryGenerationEngine.class);
    private final StoryQualityEvaluator quality = new StoryQualityEvaluator();
    private final BehavioralAnalyzer behavioral = new BehavioralAnalyzer();
    private final StoryVersionManager versions = mock(StoryVersionManager.class);
    private final StoryMetrics metrics = new StoryMetrics();
    private final UUID userId = UUID.randomUUID();

    private StarStoryEngine engine;

    @BeforeEach
    void setUp() {
        when(stories.save(any())).thenAnswer(inv -> {
            StarStory s = inv.getArgument(0);
            if (s.getId() == null) s.setId(UUID.randomUUID());
            return s;
        });
        when(analytics.findByUserId(any())).thenReturn(Optional.empty());
        engine = new StarStoryEngine(stories, usage, analytics, extraction, generation, quality, behavioral,
                versions, metrics, true);
    }

    @Test
    void disabledEngineReturnsEmptyOnGenerate() {
        StarStoryEngine off = new StarStoryEngine(stories, usage, analytics, extraction, generation, quality,
                behavioral, versions, metrics, false);
        assertTrue(off.generate(userId, StoryType.SUCCESS, "t", "h").isEmpty());
    }

    @Test
    void generateReturnsEmptyWhenGenerationDisabled() {
        when(generation.isEnabled()).thenReturn(false);
        assertTrue(engine.generate(userId, StoryType.SUCCESS, "t", "h").isEmpty());
    }

    @Test
    void generatePersistsAndEvaluatesAStory() {
        when(generation.isEnabled()).thenReturn(true);
        when(extraction.isEnabled()).thenReturn(false);
        when(generation.generate(any(), any(), any())).thenReturn(new Draft(
                "Situation text here", "Task text here", "Action text here", "Result with 20% improvement",
                "Reflection", "Lessons", "java", "kafka", "saved money"));

        Optional<StarStory> result = engine.generate(userId, StoryType.LEADERSHIP, "My story", "hint");
        assertTrue(result.isPresent());
        assertNotNull(result.get().getQualityScore());
        assertEquals(StoryType.LEADERSHIP, result.get().getStoryType());
        verify(stories).save(any());
    }

    @Test
    void createManualSetsSourceAndVersion() {
        StarStory input = StarStory.builder().title("Manual").storyType(StoryType.SUCCESS)
                .situation("s").task("t").action("a").result("r").build();
        Optional<StarStory> result = engine.createManual(userId, input);
        assertTrue(result.isPresent());
        assertEquals(StorySource.MANUAL, result.get().getSource());
        assertEquals(1, result.get().getCurrentVersion());
    }

    @Test
    void improveReturnsEmptyForUnknownStory() {
        when(generation.isEnabled()).thenReturn(true);
        when(stories.findByIdAndUserId(any(), any())).thenReturn(Optional.empty());
        assertTrue(engine.improve(userId, UUID.randomUUID(), "feedback").isEmpty());
    }

    @Test
    void improveBumpsVersionAndUpdatesFields() {
        UUID id = UUID.randomUUID();
        StarStory existing = StarStory.builder().id(id).userId(userId).title("t").storyType(StoryType.SUCCESS)
                .situation("old").task("old").action("old").result("old").currentVersion(1).build();
        when(generation.isEnabled()).thenReturn(true);
        when(extraction.isEnabled()).thenReturn(false);
        when(stories.findByIdAndUserId(id, userId)).thenReturn(Optional.of(existing));
        when(generation.generate(any(), any(), any())).thenReturn(new Draft(
                "new situation", "new task", "new action", "new result with 10%", "", "", "", "", ""));

        Optional<StarStory> result = engine.improve(userId, id, "make it better");
        assertTrue(result.isPresent());
        assertEquals(2, result.get().getCurrentVersion());
        assertEquals("new situation", result.get().getSituation());
    }

    @Test
    void rateAppliesConfidenceOverride() {
        UUID id = UUID.randomUUID();
        StarStory existing = StarStory.builder().id(id).userId(userId).title("t").storyType(StoryType.SUCCESS)
                .currentVersion(1).build();
        when(stories.findByIdAndUserId(id, userId)).thenReturn(Optional.of(existing));
        Optional<StarStory> result = engine.rate(userId, id, 42);
        assertTrue(result.isPresent());
        assertEquals(42, result.get().getConfidenceScore());
    }

    @Test
    void recordUsageReturnsEmptyForUnknownStory() {
        when(stories.findByIdAndUserId(any(), any())).thenReturn(Optional.empty());
        assertTrue(engine.recordUsage(userId, UUID.randomUUID(), "Acme", "Eng", "Round 1", "Q", "ADVANCED").isEmpty());
    }

    @Test
    void listAndGetAreEmptyWhenDisabled() {
        StarStoryEngine off = new StarStoryEngine(stories, usage, analytics, extraction, generation, quality,
                behavioral, versions, metrics, false);
        assertTrue(off.list(userId).isEmpty());
        assertTrue(off.get(userId, UUID.randomUUID()).isEmpty());
        assertTrue(off.analytics(userId).isEmpty());
    }

    @Test
    void categoriesReturnsAllStoryTypes() {
        assertEquals(StoryType.values().length, engine.categories().size());
    }

    @Test
    void recomputeAnalyticsUpsertsARow() {
        when(stories.findByUserIdOrderByUpdatedAtDesc(userId)).thenReturn(List.of(
                StarStory.builder().storyType(StoryType.SUCCESS).qualityScore(80).competencies("LEADERSHIP,OWNERSHIP").build()));
        when(usage.countByUserId(userId)).thenReturn(3L);
        when(analytics.save(any())).thenAnswer(inv -> inv.getArgument(0));

        engine.recomputeAnalytics(userId);
        verify(analytics).save(any(StoryAnalytics.class));
    }
}
