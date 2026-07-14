package ai.careerpilot.submission.api;

import ai.careerpilot.domain.ApplicationSubmissionSession;
import ai.careerpilot.security.AuthenticatedUser;
import ai.careerpilot.submission.ApplicationSubmissionSessionService;
import ai.careerpilot.submission.api.dto.ApplicationSubmissionDtos.SessionResponse;
import ai.careerpilot.submission.api.dto.ApplicationSubmissionDtos.StartRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Phase 7.16 — plain unit tests for the thin facade controller (no MockMvc/Spring context, same
 * convention as {@code ApplicationPackageDiagnosticsControllerTest}).
 */
class ApplicationSubmissionControllerTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();
    private final AuthenticatedUser user = new AuthenticatedUser(userId, UUID.randomUUID(), "a@b.com", "USER");

    private ApplicationSubmissionSessionService service;
    private ApplicationSubmissionController controller;

    @BeforeEach
    void setUp() {
        service = mock(ApplicationSubmissionSessionService.class);
        controller = new ApplicationSubmissionController(service);
    }

    private ApplicationSubmissionSession session(String status) {
        return ApplicationSubmissionSession.builder().id(sessionId).userId(userId).jobId(jobId)
                .status(status).submissionMethod(ApplicationSubmissionSession.METHOD_MANUAL).build();
    }

    // ── start ──

    @Test
    void startReturnsSessionResponseWhenServiceSucceeds() {
        when(service.start(userId, jobId, null)).thenReturn(Optional.of(session(ApplicationSubmissionSession.STATUS_CREATED)));
        SessionResponse r = controller.start(user, new StartRequest(jobId, null));
        assertEquals(sessionId, r.id());
        assertEquals(ApplicationSubmissionSession.STATUS_CREATED, r.status());
    }

    @Test
    void startPassesResumeIdThrough() {
        UUID resumeId = UUID.randomUUID();
        when(service.start(userId, jobId, resumeId)).thenReturn(Optional.of(session(ApplicationSubmissionSession.STATUS_CREATED)));
        controller.start(user, new StartRequest(jobId, resumeId));
        verify(service).start(userId, jobId, resumeId);
    }

    @Test
    void startThrowsIllegalStateWhenServiceReturnsEmpty() {
        when(service.start(userId, jobId, null)).thenReturn(Optional.empty());
        assertThrows(IllegalStateException.class, () -> controller.start(user, new StartRequest(jobId, null)));
    }

    // ── detail ──

    @Test
    void detailReturnsSessionAndAnswers() {
        ApplicationSubmissionSession s = session(ApplicationSubmissionSession.STATUS_COMPLETED);
        when(service.find(sessionId, userId)).thenReturn(Optional.of(s));
        when(service.answersFor(sessionId)).thenReturn(List.of());
        var detail = controller.detail(user, sessionId);
        assertEquals(sessionId, detail.session().id());
        assertTrue(detail.answers().isEmpty());
    }

    @Test
    void detailThrowsNotFoundWhenMissing() {
        when(service.find(sessionId, userId)).thenReturn(Optional.empty());
        assertThrows(NoSuchElementException.class, () -> controller.detail(user, sessionId));
    }

    @Test
    void detailThrowsNotFoundForAnotherUsersSession() {
        when(service.find(sessionId, userId)).thenReturn(Optional.empty()); // repo scoped by userId
        assertThrows(NoSuchElementException.class, () -> controller.detail(user, sessionId));
    }

    // ── status ──

    @Test
    void statusReturnsCurrentStatus() {
        when(service.find(sessionId, userId)).thenReturn(Optional.of(session(ApplicationSubmissionSession.STATUS_SUBMITTING)));
        var status = controller.status(user, sessionId);
        assertEquals(ApplicationSubmissionSession.STATUS_SUBMITTING, status.status());
    }

    @Test
    void statusThrowsNotFoundWhenMissing() {
        when(service.find(sessionId, userId)).thenReturn(Optional.empty());
        assertThrows(NoSuchElementException.class, () -> controller.status(user, sessionId));
    }

    // ── history ──

    @Test
    void historyDelegatesToServiceForTheSessionsJob() {
        ApplicationSubmissionSession s = session(ApplicationSubmissionSession.STATUS_COMPLETED);
        when(service.find(sessionId, userId)).thenReturn(Optional.of(s));
        when(service.history(userId, jobId)).thenReturn(List.of(s));
        List<SessionResponse> history = controller.history(user, sessionId);
        assertEquals(1, history.size());
        verify(service).history(userId, jobId);
    }

    @Test
    void historyThrowsNotFoundWhenSessionMissing() {
        when(service.find(sessionId, userId)).thenReturn(Optional.empty());
        assertThrows(NoSuchElementException.class, () -> controller.history(user, sessionId));
    }

    // ── queue ──

    @Test
    void queueDelegatesToService() {
        when(service.queue()).thenReturn(List.of(session(ApplicationSubmissionSession.STATUS_WAITING_APPROVAL)));
        List<SessionResponse> queue = controller.queue();
        assertEquals(1, queue.size());
        assertEquals(ApplicationSubmissionSession.STATUS_WAITING_APPROVAL, queue.get(0).status());
    }

    @Test
    void queueReturnsEmptyListWhenNoneWaiting() {
        when(service.queue()).thenReturn(List.of());
        assertTrue(controller.queue().isEmpty());
    }
}
