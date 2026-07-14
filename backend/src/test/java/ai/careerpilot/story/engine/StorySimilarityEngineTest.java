package ai.careerpilot.story.engine;

import ai.careerpilot.domain.StarStory;
import ai.careerpilot.repo.StarStoryRepository;
import ai.careerpilot.story.StorySource;
import ai.careerpilot.story.StoryStatus;
import ai.careerpilot.story.StoryType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StorySimilarityEngineTest {

    private final UUID userId = UUID.randomUUID();

    private StarStory story(UUID id, String skills) {
        return story(id, skills, StoryType.SUCCESS);
    }

    private StarStory story(UUID id, String skills, StoryType type) {
        return StarStory.builder().id(id).userId(userId).storyType(type)
                .status(StoryStatus.DRAFT).source(StorySource.MANUAL).skillsUsed(skills).currentVersion(1).build();
    }

    @Test
    void disabledReturnsEmptyList() {
        StarStoryRepository repo = mock(StarStoryRepository.class);
        StorySimilarityEngine engine = new StorySimilarityEngine(repo, false);
        assertTrue(engine.similarTo(userId, UUID.randomUUID(), 10).isEmpty());
    }

    @Test
    void unknownStoryReturnsEmptyList() {
        StarStoryRepository repo = mock(StarStoryRepository.class);
        UUID id = UUID.randomUUID();
        when(repo.findByIdAndUserId(id, userId)).thenReturn(Optional.empty());
        StorySimilarityEngine engine = new StorySimilarityEngine(repo, true);
        assertTrue(engine.similarTo(userId, id, 10).isEmpty());
    }

    @Test
    void similarStoriesRankedByOverlap() {
        StarStoryRepository repo = mock(StarStoryRepository.class);
        UUID targetId = UUID.randomUUID();
        StarStory target = story(targetId, "java,kafka,spring");
        StarStory close = story(UUID.randomUUID(), "java,kafka");
        StarStory far = story(UUID.randomUUID(), "python", StoryType.FAILURE);
        when(repo.findByIdAndUserId(targetId, userId)).thenReturn(Optional.of(target));
        when(repo.findByUserIdOrderByUpdatedAtDesc(userId)).thenReturn(List.of(target, close, far));

        StorySimilarityEngine engine = new StorySimilarityEngine(repo, true);
        List<StorySimilarityEngine.SimilarStory> result = engine.similarTo(userId, targetId, 10);
        assertEquals(1, result.size()); // "far" has zero overlap and is filtered out
        assertEquals(close.getId(), result.get(0).id());
    }

    @Test
    void similarityHelperIsSymmetricAndZeroForEmptySets() {
        assertEquals(0, StorySimilarityEngine.similarity(java.util.Set.of(), java.util.Set.of("a")));
        int ab = StorySimilarityEngine.similarity(java.util.Set.of("a", "b"), java.util.Set.of("b", "c"));
        int ba = StorySimilarityEngine.similarity(java.util.Set.of("b", "c"), java.util.Set.of("a", "b"));
        assertEquals(ab, ba);
        assertTrue(ab > 0);
    }
}
