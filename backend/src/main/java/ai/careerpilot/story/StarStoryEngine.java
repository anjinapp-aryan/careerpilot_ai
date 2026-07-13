package ai.careerpilot.story;

import ai.careerpilot.domain.StarStory;
import ai.careerpilot.domain.StoryAnalytics;
import ai.careerpilot.domain.StoryUsage;
import ai.careerpilot.repo.StarStoryRepository;
import ai.careerpilot.repo.StoryAnalyticsRepository;
import ai.careerpilot.repo.StoryUsageRepository;
import ai.careerpilot.story.analyzer.BehavioralAnalyzer;
import ai.careerpilot.story.engine.StoryVersionManager;
import ai.careerpilot.story.evaluator.StoryQualityEvaluator;
import ai.careerpilot.story.extractor.StoryExtractionEngine;
import ai.careerpilot.story.extractor.StoryExtractionEngine.RawMaterial;
import ai.careerpilot.story.generator.StoryGenerationEngine;
import ai.careerpilot.story.generator.StoryGenerationEngine.Draft;
import ai.careerpilot.story.metrics.StoryMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Phase 7.15 — the orchestrating facade tying extraction → generation → quality evaluation →
 * competency classification → persistence together, equivalent in role to {@code KnowledgeAggregator}
 * / {@code ResumeTailoringWorker}'s orchestration in the precedent phases. Gated by
 * {@code story.engine.enabled} (default off): when dark, every write is a no-op and every read
 * returns empty, so no caller needs its own guard.
 */
@Service
public class StarStoryEngine {

    private static final Logger log = LoggerFactory.getLogger(StarStoryEngine.class);

    private final StarStoryRepository stories;
    private final StoryUsageRepository usageRepo;
    private final StoryAnalyticsRepository analyticsRepo;
    private final StoryExtractionEngine extraction;
    private final StoryGenerationEngine generation;
    private final StoryQualityEvaluator quality;
    private final BehavioralAnalyzer behavioral;
    private final StoryVersionManager versions;
    private final StoryMetrics metrics;
    private final ObjectMapper mapper = new ObjectMapper();
    private final boolean enabled;

    public StarStoryEngine(StarStoryRepository stories, StoryUsageRepository usageRepo,
                           StoryAnalyticsRepository analyticsRepo, StoryExtractionEngine extraction,
                           StoryGenerationEngine generation, StoryQualityEvaluator quality,
                           BehavioralAnalyzer behavioral, StoryVersionManager versions, StoryMetrics metrics,
                           @Value("${story.engine.enabled:false}") boolean enabled) {
        this.stories = stories;
        this.usageRepo = usageRepo;
        this.analyticsRepo = analyticsRepo;
        this.extraction = extraction;
        this.generation = generation;
        this.quality = quality;
        this.behavioral = behavioral;
        this.versions = versions;
        this.metrics = metrics;
        this.enabled = enabled;
    }

    public boolean isEnabled() { return enabled; }

    // ── Reads ──

    public List<StarStory> list(UUID userId) {
        if (!enabled) return List.of();
        return stories.findByUserIdOrderByUpdatedAtDesc(userId);
    }

    public Optional<StarStory> get(UUID userId, UUID id) {
        if (!enabled) return Optional.empty();
        return stories.findByIdAndUserId(id, userId);
    }

    public List<StoryType> categories() {
        return List.of(StoryType.values());
    }

    // ── Writes ──

    /** Extract raw material and generate a full draft STAR story, then evaluate + persist it. */
    @Transactional
    public Optional<StarStory> generate(UUID userId, StoryType storyType, String title, String hint) {
        if (!enabled || !generation.isEnabled()) return Optional.empty();
        RawMaterial material = extraction.isEnabled() ? extraction.extract(userId) : new RawMaterial(null, null, List.of(), List.of());
        Draft draft = generation.generate(storyType, material, hint);

        StarStory story = StarStory.builder()
                .userId(userId)
                .title(title == null || title.isBlank() ? defaultTitle(storyType) : title)
                .storyType(storyType == null ? StoryType.SUCCESS : storyType)
                .status(StoryStatus.DRAFT)
                .source(StorySource.GENERATED)
                .situation(draft.situation())
                .task(draft.task())
                .action(draft.action())
                .result(draft.result())
                .reflection(draft.reflection())
                .lessonsLearned(draft.lessonsLearned())
                .skillsUsed(draft.skillsUsed())
                .technologiesUsed(draft.technologiesUsed())
                .businessImpact(draft.businessImpact())
                .currentVersion(1)
                .build();

        evaluateAndClassify(story);
        StarStory saved = stories.save(story);
        versions.snapshot(saved, "generated", StorySource.GENERATED);
        metrics.increment("storiesGenerated");
        recomputeAnalytics(userId);
        log.info("STORY_INTEL generated story id={} user={} type={}", saved.getId(), userId, saved.getStoryType());
        return Optional.of(saved);
    }

    /** Manually create/save a story with fields the user supplied directly (not AI-generated). */
    @Transactional
    public Optional<StarStory> createManual(UUID userId, StarStory input) {
        if (!enabled) return Optional.empty();
        input.setUserId(userId);
        input.setSource(StorySource.MANUAL);
        input.setStatus(input.getStatus() == null ? StoryStatus.DRAFT : input.getStatus());
        input.setCurrentVersion(1);
        evaluateAndClassify(input);
        StarStory saved = stories.save(input);
        versions.snapshot(saved, "created", StorySource.MANUAL);
        metrics.increment("storiesCreated");
        recomputeAnalytics(userId);
        return Optional.of(saved);
    }

    /** Regenerate/improve an existing story's narrative from feedback, bumping its version. */
    @Transactional
    public Optional<StarStory> improve(UUID userId, UUID id, String feedback) {
        if (!enabled || !generation.isEnabled()) return Optional.empty();
        StarStory story = stories.findByIdAndUserId(id, userId).orElse(null);
        if (story == null) return Optional.empty();

        RawMaterial material = extraction.isEnabled() ? extraction.extract(userId) : new RawMaterial(null, null, List.of(), List.of());
        String hint = "Improve this existing story. Current result: " + story.getResult()
                + (feedback != null && !feedback.isBlank() ? ". Feedback: " + feedback : "");
        Draft draft = generation.generate(story.getStoryType(), material, hint);

        if (hasContent(draft.situation())) story.setSituation(draft.situation());
        if (hasContent(draft.task())) story.setTask(draft.task());
        if (hasContent(draft.action())) story.setAction(draft.action());
        if (hasContent(draft.result())) story.setResult(draft.result());
        if (hasContent(draft.reflection())) story.setReflection(draft.reflection());
        if (hasContent(draft.lessonsLearned())) story.setLessonsLearned(draft.lessonsLearned());
        if (hasContent(draft.businessImpact())) story.setBusinessImpact(draft.businessImpact());

        story.setCurrentVersion(story.getCurrentVersion() + 1);
        evaluateAndClassify(story);
        StarStory saved = stories.save(story);
        versions.snapshot(saved, "improved:" + (feedback == null ? "" : feedback), StorySource.IMPROVED);
        metrics.increment("storiesImproved");
        recomputeAnalytics(userId);
        return Optional.of(saved);
    }

    /** Recompute quality/competency for the current field values and persist. */
    @Transactional
    public Optional<StarStory> rate(UUID userId, UUID id, Integer confidenceOverride) {
        if (!enabled) return Optional.empty();
        StarStory story = stories.findByIdAndUserId(id, userId).orElse(null);
        if (story == null) return Optional.empty();
        if (confidenceOverride != null) story.setConfidenceScore(confidenceOverride);
        evaluateAndClassify(story);
        StarStory saved = stories.save(story);
        recomputeAnalytics(userId);
        return Optional.of(saved);
    }

    @Transactional
    public Optional<StoryUsage> recordUsage(UUID userId, UUID storyId, String company, String role,
                                            String round, String question, String outcome) {
        if (!enabled) return Optional.empty();
        StarStory story = stories.findByIdAndUserId(storyId, userId).orElse(null);
        if (story == null) return Optional.empty();
        StoryUsage usage = usageRepo.save(StoryUsage.builder()
                .starStoryId(storyId).userId(userId).companyName(company).targetRole(role)
                .interviewRound(round).question(question).outcome(outcome).build());
        metrics.increment("usageRecorded");
        recomputeAnalytics(userId);
        return Optional.of(usage);
    }

    public Optional<StoryAnalytics> analytics(UUID userId) {
        if (!enabled) return Optional.empty();
        return analyticsRepo.findByUserId(userId);
    }

    // ── Internals ──

    void evaluateAndClassify(StarStory story) {
        var evaluation = quality.evaluate(story);
        story.setQualityScore(evaluation.qualityScore());
        if (story.getConfidenceScore() == null) story.setConfidenceScore(evaluation.confidenceScore());
        story.setMissingSections(String.join(",", evaluation.missingSections()));
        story.setImprovementSuggestions(String.join(" | ", evaluation.improvementSuggestions()));
        story.setQualityBreakdown(writeJson(evaluation.breakdown()));

        var topCompetencies = behavioral.topCompetencies(story, 6);
        story.setCompetencies(topCompetencies.stream().map(Enum::name).collect(Collectors.joining(",")));

        story.setStatus(evaluation.missingSections().isEmpty() ? StoryStatus.COMPLETE : StoryStatus.DRAFT);
    }

    @Transactional
    public void recomputeAnalytics(UUID userId) {
        List<StarStory> all = stories.findByUserIdOrderByUpdatedAtDesc(userId);
        Map<String, Long> byCategory = all.stream()
                .filter(s -> s.getStoryType() != null)
                .collect(Collectors.groupingBy(s -> s.getStoryType().name(), Collectors.counting()));
        Map<String, Long> byCompetency = new LinkedHashMap<>();
        for (StarStory s : all) {
            if (s.getCompetencies() == null || s.getCompetencies().isBlank()) continue;
            for (String c : s.getCompetencies().split(",")) {
                if (c.isBlank()) continue;
                byCompetency.merge(c, 1L, Long::sum);
            }
        }
        int avgQuality = (int) Math.round(all.stream()
                .filter(s -> s.getQualityScore() != null)
                .mapToInt(StarStory::getQualityScore).average().orElse(0));

        StoryAnalytics analytics = analyticsRepo.findByUserId(userId).orElseGet(() ->
                StoryAnalytics.builder().userId(userId).totalStories(0).usageCount(0).recommendationCount(0).build());
        analytics.setTotalStories(all.size());
        analytics.setAvgQualityScore(avgQuality);
        analytics.setByCategory(writeJson(byCategory));
        analytics.setByCompetency(writeJson(byCompetency));
        analytics.setUsageCount((int) usageRepo.countByUserId(userId));
        analytics.setLastComputedAt(java.time.Instant.now());
        analyticsRepo.save(analytics);
    }

    private static boolean hasContent(String s) { return s != null && !s.isBlank(); }

    private static String defaultTitle(StoryType type) {
        return type == null ? "Untitled Story" : type.name().replace('_', ' ') + " story";
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
