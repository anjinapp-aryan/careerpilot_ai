package ai.careerpilot.story.engine;

import ai.careerpilot.domain.StarStory;
import ai.careerpilot.repo.StarStoryRepository;
import ai.careerpilot.story.StoryType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Phase 7.15 — natural-language-ish search over a user's stories (e.g. "find my best leadership
 * story"). Simple keyword/tag/type matching per CLAUDE.md guidance — a full semantic/embedding
 * search is explicitly NOT required (the repo's {@code embedding} columns are unwired everywhere
 * else; this deliberately does not become the first place to wire pgvector).
 */
@Component
public class StorySearchEngine {

    private final StarStoryRepository stories;
    private final boolean enabled;

    public StorySearchEngine(StarStoryRepository stories,
                             @Value("${story.search.enabled:false}") boolean enabled) {
        this.stories = stories;
        this.enabled = enabled;
    }

    public boolean isEnabled() { return enabled; }

    public record SearchHit(StarStory story, int score) {}

    public List<SearchHit> search(UUID userId, String query, int limit) {
        if (!enabled) return List.of();
        List<StarStory> all = stories.findByUserIdOrderByUpdatedAtDesc(userId);
        if (query == null || query.isBlank()) {
            return all.stream().limit(limit).map(s -> new SearchHit(s, 0)).toList();
        }
        String lower = query.toLowerCase();
        StoryType typeHint = matchType(lower);
        List<String> terms = Arrays.stream(lower.split("\\W+")).filter(t -> t.length() > 2).toList();

        return all.stream()
                .map(s -> new SearchHit(s, score(s, terms, typeHint)))
                .filter(h -> h.score() > 0)
                .sorted(Comparator.comparingInt(SearchHit::score).reversed())
                .limit(limit)
                .toList();
    }

    private int score(StarStory s, List<String> terms, StoryType typeHint) {
        String haystack = String.join(" ", nullSafe(s.getTitle()), nullSafe(s.getSituation()),
                nullSafe(s.getTask()), nullSafe(s.getAction()), nullSafe(s.getResult()),
                nullSafe(s.getSkillsUsed()), nullSafe(s.getCompetencies()),
                s.getStoryType() == null ? "" : s.getStoryType().name()).toLowerCase();
        int score = 0;
        for (String term : terms) {
            if (haystack.contains(term)) score += 10;
        }
        if (typeHint != null && typeHint.equals(s.getStoryType())) score += 40;
        if (haystack.contains("best") || haystack.contains("strong")) score += 0; // filler words no-op
        if (s.getQualityScore() != null) score += s.getQualityScore() / 20; // slight quality tiebreak
        return score;
    }

    private StoryType matchType(String lower) {
        for (StoryType t : StoryType.values()) {
            String friendly = t.name().replace('_', ' ').toLowerCase();
            if (lower.contains(friendly) || lower.contains(t.name().toLowerCase())) return t;
        }
        return null;
    }

    private static String nullSafe(String s) { return s == null ? "" : s; }
}
