package ai.careerpilot.story.recommender;

import ai.careerpilot.domain.CompanyKnowledge;
import ai.careerpilot.domain.StarStory;
import ai.careerpilot.domain.StoryRecommendation;
import ai.careerpilot.repo.CompanyKnowledgeRepository;
import ai.careerpilot.repo.StarStoryRepository;
import ai.careerpilot.repo.StoryRecommendationRepository;
import ai.careerpilot.story.StoryType;
import ai.careerpilot.story.metrics.StoryMetrics;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Phase 7.15 — recommends the best-fit stored stories for a given company/role/question, reusing
 * {@code CompanyKnowledgeRepository} (Company Intelligence, read-only) to bias by company culture
 * when available. No duplicate scoring logic — deterministic keyword overlap only, same convention
 * as {@code CompanyScoringService}/{@code StoryQualityEvaluator}.
 */
@Service
public class StoryRecommendationEngine {

    private final StarStoryRepository stories;
    private final StoryRecommendationRepository recommendations;
    private final CompanyKnowledgeRepository companyKnowledge;
    private final StoryMetrics metrics;
    private final boolean enabled;

    public StoryRecommendationEngine(StarStoryRepository stories, StoryRecommendationRepository recommendations,
                                     CompanyKnowledgeRepository companyKnowledge, StoryMetrics metrics,
                                     @Value("${story.recommendation.enabled:false}") boolean enabled) {
        this.stories = stories;
        this.recommendations = recommendations;
        this.companyKnowledge = companyKnowledge;
        this.metrics = metrics;
        this.enabled = enabled;
    }

    public boolean isEnabled() { return enabled; }

    @Transactional
    public List<StoryRecommendation> recommend(UUID userId, String companyName, String targetRole, String question, int limit) {
        if (!enabled) return List.of();
        List<StarStory> candidates = stories.findByUserIdOrderByUpdatedAtDesc(userId);
        if (candidates.isEmpty()) return List.of();

        Set<String> companySignals = companySignals(userId, companyName);
        List<StoryRecommendation> ranked = new ArrayList<>();
        for (StarStory story : candidates) {
            int score = score(story, targetRole, question, companySignals);
            if (score <= 0) continue;
            ranked.add(recommendations.save(StoryRecommendation.builder()
                    .userId(userId).starStoryId(story.getId()).companyName(companyName).targetRole(targetRole)
                    .question(question).matchScore(score).reason(reason(story, targetRole, question, companySignals))
                    .build()));
        }
        ranked.sort(Comparator.comparingInt(StoryRecommendation::getMatchScore).reversed());
        metrics.increment("recommendationsGenerated");
        return ranked.stream().limit(limit).toList();
    }

    private int score(StarStory story, String targetRole, String question, Set<String> companySignals) {
        int score = story.getQualityScore() == null ? 30 : story.getQualityScore() / 2;
        String haystack = lower(story.getTitle(), story.getSituation(), story.getTask(), story.getAction(),
                story.getResult(), story.getSkillsUsed(), story.getTechnologiesUsed(), story.getCompetencies(),
                story.getStoryType() == null ? "" : story.getStoryType().name());

        if (question != null) {
            StoryType hinted = hintFromQuestion(question);
            if (hinted != null && hinted.equals(story.getStoryType())) score += 30;
            for (String term : question.toLowerCase().split("\\W+")) {
                if (term.length() > 3 && haystack.contains(term)) score += 5;
            }
        }
        if (targetRole != null) {
            for (String term : targetRole.toLowerCase().split("\\W+")) {
                if (term.length() > 2 && haystack.contains(term)) score += 4;
            }
        }
        for (String signal : companySignals) {
            if (haystack.contains(signal)) score += 6;
        }
        return Math.max(0, Math.min(100, score));
    }

    private String reason(StarStory story, String targetRole, String question, Set<String> companySignals) {
        List<String> reasons = new ArrayList<>();
        if (story.getQualityScore() != null && story.getQualityScore() >= 70) reasons.add("high quality score");
        StoryType hinted = question == null ? null : hintFromQuestion(question);
        if (hinted != null && hinted.equals(story.getStoryType())) reasons.add("matches question type " + hinted);
        if (!companySignals.isEmpty()) reasons.add("aligns with known company context");
        if (targetRole != null && !targetRole.isBlank()) reasons.add("relevant to role: " + targetRole);
        return reasons.isEmpty() ? "general fit" : String.join("; ", reasons);
    }

    private Set<String> companySignals(UUID userId, String companyName) {
        if (companyName == null || companyName.isBlank()) return Set.of();
        Optional<CompanyKnowledge> knowledge = companyKnowledge.findByUserIdAndNormalizedName(
                userId, companyName.strip().toLowerCase().replaceAll("[^a-z0-9]+", ""));
        if (knowledge.isEmpty() || knowledge.get().getIndustry() == null) return Set.of();
        return Set.of(knowledge.get().getIndustry().toLowerCase());
    }

    private static StoryType hintFromQuestion(String question) {
        String lower = question.toLowerCase();
        for (StoryType t : StoryType.values()) {
            if (lower.contains(t.name().replace('_', ' ').toLowerCase())) return t;
        }
        if (lower.contains("conflict") || lower.contains("disagree")) return StoryType.CONFLICT_RESOLUTION;
        if (lower.contains("fail")) return StoryType.FAILURE;
        if (lower.contains("lead")) return StoryType.LEADERSHIP;
        return null;
    }

    private static String lower(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) if (p != null) sb.append(' ').append(p.toLowerCase());
        return sb.toString();
    }
}
