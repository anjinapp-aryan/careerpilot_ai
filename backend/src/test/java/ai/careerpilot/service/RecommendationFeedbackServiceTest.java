package ai.careerpilot.service;

import ai.careerpilot.domain.RecommendationFeedback;
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
        return new RecommendationFeedbackService(repo, enabled);
    }

    @Test
    void disabledIsANoOpAndNeverWrites() {
        RecommendationFeedbackRepository repo = mock(RecommendationFeedbackRepository.class);
        RecommendationFeedbackService svc = new RecommendationFeedbackService(repo, false);
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
                () -> new RecommendationFeedbackService(repo, true).record(userId, jobId, "maybe", null));
    }

    @Test
    void validActionHelperIsCaseInsensitive() {
        assertTrue(RecommendationFeedbackService.isValidAction("apply_later"));
        assertTrue(RecommendationFeedbackService.isValidAction("REJECT"));
        assertFalse(RecommendationFeedbackService.isValidAction("maybe"));
        assertFalse(RecommendationFeedbackService.isValidAction(null));
    }
}
