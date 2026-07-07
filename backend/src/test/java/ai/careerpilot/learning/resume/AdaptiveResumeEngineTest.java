package ai.careerpilot.learning.resume;

import ai.careerpilot.domain.ResumeLearning;
import ai.careerpilot.repo.ResumeLearningRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdaptiveResumeEngineTest {

    private final UUID userId = UUID.randomUUID();

    @Test
    void disabledEngineReturnsEmptyWithoutQuerying() {
        ResumeLearningRepository repo = mock(ResumeLearningRepository.class);
        AdaptiveResumeEngine engine = new AdaptiveResumeEngine(repo, false);
        assertTrue(engine.bestVersion(userId).isEmpty());
        assertFalse(engine.isEnabled());
        verifyNoInteractions(repo);
    }

    @Test
    void enabledEngineDelegatesToRepository() {
        ResumeLearningRepository repo = mock(ResumeLearningRepository.class);
        ResumeLearning best = ResumeLearning.builder().userId(userId).resumeVersion("v2").bestVersion(true).build();
        when(repo.findByUserIdAndBestVersionTrue(userId)).thenReturn(Optional.of(best));
        AdaptiveResumeEngine engine = new AdaptiveResumeEngine(repo, true);

        Optional<ResumeLearning> result = engine.bestVersion(userId);
        assertTrue(result.isPresent());
        assertEquals("v2", result.get().getResumeVersion());
    }
}
