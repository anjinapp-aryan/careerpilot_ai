package ai.careerpilot.service;

import ai.careerpilot.domain.RecommendationFeedback;
import ai.careerpilot.memory.CareerMemoryService;
import ai.careerpilot.repo.RecommendationFeedbackRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Phase 2C-4 — feedback capture must be a no-op when the flag is off (so the automatic-capture path
 * is always safe to call), normalise the action, reject unknown actions, and persist a row when on.
 */
class RecommendationFeedbackServiceTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();

    private RecommendationFeedbackService service(RecommendationFeedbackRepository repo, boolean enabled) {
        when(repo.save(any(RecommendationFeedback.class))).thenAnswer(inv -> inv.getArgument(0));
        return new RecommendationFeedbackService(repo, mock(CareerMemoryService.class), enabled);
    }

    @Test
    void disabledIsANoOpAndNeverWrites() {
        RecommendationFeedbackRepository repo = mock(RecommendationFeedbackRepository.class);
        RecommendationFeedbackService svc = new RecommendationFeedbackService(repo, mock(CareerMemoryService.class), false);
        assertTrue(svc.record(userId, jobId, "APPROVE", "x").isEmpty());
        verify(repo, never()).save(any());
    }

    @Test
    void enabledPersistsNormalisedAction() {
        RecommendationFeedbackRepository repo = mock(RecommendationFeedbackRepository.class);
        Optional<RecommendationFeedback> saved = service(repo, true).record(userId, jobId, " approve ", "great fit");
        assertTrue(saved.isPresent());
        ArgumentCaptor<RecommendationFeedback> captor = ArgumentCaptor.forClass(RecommendationFeedback.class);
        verify(repo).save(captor.capture());
        assertEquals("APPROVE", captor.getValue().getAction());
        assertEquals("great fit", captor.getValue().getReason());
        assertEquals(userId, captor.getValue().getUserId());
    }

    @Test
    void allCanonicalActionsAccepted() {
        RecommendationFeedbackRepository repo = mock(RecommendationFeedbackRepository.class);
        RecommendationFeedbackService svc = service(repo, true);
        for (String a : new String[]{"APPROVE", "REJECT", "IGNORE", "SAVE", "APPLY_LATER"}) {
            assertTrue(svc.record(userId, jobId, a, null).isPresent(), a);
        }
    }

    @Test
    void unknownActionThrowsWhenEnabled() {
        RecommendationFeedbackRepository repo = mock(RecommendationFeedbackRepository.class);
        assertThrows(IllegalArgumentException.class,
                () -> new RecommendationFeedbackService(repo, mock(CareerMemoryService.class), true)
                        .record(userId, jobId, "maybe", null));
    }

    @Test
    void enabledSaveAlsoCapturesCareerMemory() {
        RecommendationFeedbackRepository repo = mock(RecommendationFeedbackRepository.class);
        CareerMemoryService careerMemory = mock(CareerMemoryService.class);
        when(repo.save(any(RecommendationFeedback.class))).thenAnswer(inv -> inv.getArgument(0));
        new RecommendationFeedbackService(repo, careerMemory, true).record(userId, jobId, "REJECT", "relocation required");
        ArgumentCaptor<RecommendationFeedback> captor = ArgumentCaptor.forClass(RecommendationFeedback.class);
        verify(careerMemory).captureFeedback(captor.capture());
        assertEquals("relocation required", captor.getValue().getReason());
    }

    @Test
    void careerMemoryFailureNeverBreaksFeedbackSave() {
        RecommendationFeedbackRepository repo = mock(RecommendationFeedbackRepository.class);
        CareerMemoryService careerMemory = mock(CareerMemoryService.class);
        when(repo.save(any(RecommendationFeedback.class))).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new RuntimeException("boom")).when(careerMemory).captureFeedback(any());
        Optional<RecommendationFeedback> saved =
                new RecommendationFeedbackService(repo, careerMemory, true).record(userId, jobId, "APPROVE", null);
        assertTrue(saved.isPresent());
    }

    @Test
    void validActionHelperIsCaseInsensitive() {
        assertTrue(RecommendationFeedbackService.isValidAction("apply_later"));
        assertTrue(RecommendationFeedbackService.isValidAction("REJECT"));
        assertFalse(RecommendationFeedbackService.isValidAction("maybe"));
        assertFalse(RecommendationFeedbackService.isValidAction(null));
    }
}
