package ai.careerpilot.story.engine;

import ai.careerpilot.domain.StarStory;
import ai.careerpilot.repo.StarStoryRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Phase 7.15 — finds stories similar to a given one via Jaccard similarity over skills/technologies/
 * competencies token sets, mirroring {@code CompanySimilarityService}'s approach.
 */
@Component
public class StorySimilarityEngine {

    private final StarStoryRepository stories;
    private final boolean enabled;

    public StorySimilarityEngine(StarStoryRepository stories,
                                 @Value("${story.search.enabled:false}") boolean enabled) {
        this.stories = stories;
        this.enabled = enabled;
    }

    public boolean isEnabled() { return enabled; }

    public record SimilarStory(UUID id, String title, int similarity) {}

    public List<SimilarStory> similarTo(UUID userId, UUID storyId, int limit) {
        if (!enabled) return List.of();
        StarStory target = stories.findByIdAndUserId(storyId, userId).orElse(null);
        if (target == null) return List.of();
        Set<String> targetTokens = tokens(target);
        return stories.findByUserIdOrderByUpdatedAtDesc(userId).stream()
                .filter(s -> !s.getId().equals(storyId))
                .map(s -> new SimilarStory(s.getId(), s.getTitle(), similarity(targetTokens, tokens(s))))
                .filter(s -> s.similarity() > 0)
                .sorted(Comparator.comparingInt(SimilarStory::similarity).reversed())
                .limit(limit)
                .toList();
    }

    static int similarity(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return union.isEmpty() ? 0 : (int) Math.round(100.0 * intersection.size() / union.size());
    }

    private static Set<String> tokens(StarStory s) {
        Set<String> tokens = new HashSet<>();
        addTokens(tokens, s.getSkillsUsed());
        addTokens(tokens, s.getTechnologiesUsed());
        addTokens(tokens, s.getCompetencies());
        if (s.getStoryType() != null) tokens.add(s.getStoryType().name().toLowerCase());
        return tokens;
    }

    private static void addTokens(Set<String> tokens, String csv) {
        if (csv == null || csv.isBlank()) return;
        for (String t : csv.split("[,;]")) {
            String trimmed = t.strip().toLowerCase();
            if (!trimmed.isEmpty()) tokens.add(trimmed);
        }
    }
}
