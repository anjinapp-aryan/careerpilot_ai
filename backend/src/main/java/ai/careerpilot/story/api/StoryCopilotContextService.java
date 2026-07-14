package ai.careerpilot.story.api;

import ai.careerpilot.domain.StarStory;
import ai.careerpilot.repo.StarStoryRepository;
import ai.careerpilot.story.StarStoryEngine;
import ai.careerpilot.story.recommender.StoryRecommendationEngine;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Phase 7.15 — supplies a {@code SkillContext}-shaped context block for Copilot integration,
 * mirroring {@code CompanyExplainContextService}. Read-only; never writes.
 */
@Service
public class StoryCopilotContextService {

    private final StarStoryEngine engine;
    private final StarStoryRepository stories;
    private final StoryRecommendationEngine recommender;

    public StoryCopilotContextService(StarStoryEngine engine, StarStoryRepository stories,
                                      StoryRecommendationEngine recommender) {
        this.engine = engine;
        this.stories = stories;
        this.recommender = recommender;
    }

    public record StoryCopilotContext(boolean enabled, List<StarStory> stories) {}

    public StoryCopilotContext forUser(UUID userId) {
        if (!engine.isEnabled()) return new StoryCopilotContext(false, List.of());
        return new StoryCopilotContext(true, engine.list(userId));
    }

    public StarStoryEngine engine() { return engine; }
    public StarStoryRepository repository() { return stories; }
    public StoryRecommendationEngine recommender() { return recommender; }
}
