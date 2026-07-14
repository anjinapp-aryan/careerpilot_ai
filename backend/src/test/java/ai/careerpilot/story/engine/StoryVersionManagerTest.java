package ai.careerpilot.story.engine;

import ai.careerpilot.domain.StarStory;
import ai.careerpilot.domain.StoryVersion;
import ai.careerpilot.repo.StoryVersionRepository;
import ai.careerpilot.story.StorySource;
import ai.careerpilot.story.StoryStatus;
import ai.careerpilot.story.StoryType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class StoryVersionManagerTest {

    private final StoryVersionRepository repo = mock(StoryVersionRepository.class);
    private final StoryVersionManager manager = new StoryVersionManager(repo);

    private StarStory story() {
        return StarStory.builder().id(UUID.randomUUID()).userId(UUID.randomUUID())
                .storyType(StoryType.SUCCESS).status(StoryStatus.DRAFT).source(StorySource.MANUAL)
                .situation("s").task("t").action("a").result("r").currentVersion(1).build();
    }

    @Test
    void snapshotPersistsAVersionRow() {
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        StoryVersion saved = manager.snapshot(story(), "created", StorySource.MANUAL);
        assertNotNull(saved);
        assertEquals(1, saved.getVersion());
        assertEquals("created", saved.getChangeSummary());
        verify(repo).save(any());
    }

    @Test
    void applySnapshotRestoresFieldsFromJson() {
        StarStory original = story();
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        StoryVersion v1 = manager.snapshot(original, "created", StorySource.MANUAL);

        StarStory head = story();
        head.setSituation("changed");
        boolean applied = manager.applySnapshot(head, v1);
        assertTrue(applied);
        assertEquals("s", head.getSituation());
    }

    @Test
    void applySnapshotReturnsFalseForBlankSnapshot() {
        StoryVersion blank = StoryVersion.builder().snapshot(null).build();
        assertFalse(manager.applySnapshot(story(), blank));
    }

    @Test
    void historyDelegatesToRepository() {
        UUID storyId = UUID.randomUUID();
        manager.history(storyId);
        verify(repo).findByStarStoryIdOrderByVersionDesc(storyId);
    }
}
