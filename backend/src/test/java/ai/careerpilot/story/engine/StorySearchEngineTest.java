package ai.careerpilot.story.engine;

import ai.careerpilot.domain.StarStory;
import ai.careerpilot.repo.StarStoryRepository;
import ai.careerpilot.story.StorySource;
import ai.careerpilot.story.StoryStatus;
import ai.careerpilot.story.StoryType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StorySearchEngineTest {

    private final UUID userId = UUID.randomUUID();

    private StarStory story(String title, StoryType type, Integer quality) {
        return StarStory.builder().id(UUID.randomUUID()).userId(userId).title(title).storyType(type)
                .status(StoryStatus.DRAFT).source(StorySource.MANUAL).qualityScore(quality).currentVersion(1).build();
    }

    @Test
    void disabledReturnsEmpty() {
        StarStoryRepository repo = mock(StarStoryRepository.class);
        StorySearchEngine engine = new StorySearchEngine(repo, false);
        assertTrue(engine.search(userId, "leadership", 10).isEmpty());
    }

    @Test
    void blankQueryReturnsAllUpToLimit() {
        StarStoryRepository repo = mock(StarStoryRepository.class);
        when(repo.findByUserIdOrderByUpdatedAtDesc(userId)).thenReturn(
                List.of(story("A", StoryType.SUCCESS, 50), story("B", StoryType.FAILURE, 60)));
        StorySearchEngine engine = new StorySearchEngine(repo, true);
        assertEquals(2, engine.search(userId, null, 10).size());
    }

    @Test
    void findsBestLeadershipStoryByTypeHint() {
        StarStoryRepository repo = mock(StarStoryRepository.class);
        StarStory leadership = story("Rescued the launch", StoryType.LEADERSHIP, 80);
        StarStory other = story("Random bug fix", StoryType.PROBLEM_SOLVING, 90);
        when(repo.findByUserIdOrderByUpdatedAtDesc(userId)).thenReturn(List.of(leadership, other));

        StorySearchEngine engine = new StorySearchEngine(repo, true);
        List<StorySearchEngine.SearchHit> hits = engine.search(userId, "find my best leadership story", 10);
        assertFalse(hits.isEmpty());
        assertEquals(leadership.getId(), hits.get(0).story().getId());
    }

    @Test
    void noMatchesReturnsEmptyList() {
        StarStoryRepository repo = mock(StarStoryRepository.class);
        when(repo.findByUserIdOrderByUpdatedAtDesc(userId)).thenReturn(
                List.of(story("Unrelated", StoryType.SUCCESS, 10)));
        StorySearchEngine engine = new StorySearchEngine(repo, true);
        assertTrue(engine.search(userId, "zzz_no_match_keyword_xyz", 10).isEmpty());
    }

    @Test
    void resultsAreLimited() {
        StarStoryRepository repo = mock(StarStoryRepository.class);
        when(repo.findByUserIdOrderByUpdatedAtDesc(userId)).thenReturn(
                List.of(story("Success one", StoryType.SUCCESS, 10), story("Success two", StoryType.SUCCESS, 20),
                        story("Success three", StoryType.SUCCESS, 30)));
        StorySearchEngine engine = new StorySearchEngine(repo, true);
        assertTrue(engine.search(userId, "success", 2).size() <= 2);
    }
}
