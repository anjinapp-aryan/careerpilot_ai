package ai.careerpilot.api;

import ai.careerpilot.api.dto.ResumeIntelligenceDtos.ResumeAnalysisStatusDto;
import ai.careerpilot.security.AuthenticatedUser;
import ai.careerpilot.service.ResumeService;
import ai.careerpilot.service.ResumeVersionService;
import ai.careerpilot.service.profile.ResumeIntelligenceCenterService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Phase 8.2 — the Resume Intelligence Center endpoints on ResumeController: feature-flag gating
 * (404 when disabled, matching CandidateProfileController's established convention) and
 * delegate-to-service contract. Existing upload/list/versions/download endpoints are untouched
 * and covered elsewhere.
 */
class ResumeControllerIntelligenceTest {

    private final AuthenticatedUser user =
            new AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID(), "u@x.io", "OWNER");
    private final UUID resumeId = UUID.randomUUID();

    private ResumeController controller(ResumeIntelligenceCenterService intelligence) {
        return new ResumeController(mock(ResumeService.class), mock(ResumeVersionService.class), intelligence);
    }

    @Test
    void dashboardReturns404WhenDisabled() {
        ResumeIntelligenceCenterService svc = mock(ResumeIntelligenceCenterService.class);
        when(svc.isEnabled()).thenReturn(false);

        assertEquals(HttpStatus.NOT_FOUND, controller(svc).dashboard(user).getStatusCode());
        verify(svc, never()).dashboard(any());
    }

    @Test
    void dashboardDelegatesWhenEnabled() {
        ResumeIntelligenceCenterService svc = mock(ResumeIntelligenceCenterService.class);
        when(svc.isEnabled()).thenReturn(true);
        when(svc.dashboard(user.userId())).thenReturn(List.of());

        ResponseEntity<?> resp = controller(svc).dashboard(user);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(svc).dashboard(user.userId());
    }

    @Test
    void analyzeReturns404WhenDisabled() {
        ResumeIntelligenceCenterService svc = mock(ResumeIntelligenceCenterService.class);
        when(svc.isEnabled()).thenReturn(false);

        assertEquals(HttpStatus.NOT_FOUND, controller(svc).analyze(user, resumeId).getStatusCode());
        verify(svc, never()).analyze(any(), any());
    }

    @Test
    void analyzeAndReanalyzeBothDelegateToTheSameServiceMethod() {
        ResumeIntelligenceCenterService svc = mock(ResumeIntelligenceCenterService.class);
        when(svc.isEnabled()).thenReturn(true);
        ResumeAnalysisStatusDto status = new ResumeAnalysisStatusDto(resumeId, "ANALYZED", null, null, null, null, null);
        when(svc.analyze(user.userId(), resumeId)).thenReturn(status);

        ResumeController c = controller(svc);
        assertEquals(HttpStatus.OK, c.analyze(user, resumeId).getStatusCode());
        assertEquals(HttpStatus.OK, c.reanalyze(user, resumeId).getStatusCode());
        verify(svc, times(2)).analyze(user.userId(), resumeId);
    }

    @Test
    void statusDelegatesWhenEnabled() {
        ResumeIntelligenceCenterService svc = mock(ResumeIntelligenceCenterService.class);
        when(svc.isEnabled()).thenReturn(true);
        when(svc.status(user.userId(), resumeId))
                .thenReturn(new ResumeAnalysisStatusDto(resumeId, "NOT_ANALYZED", null, null, null, null, null));

        ResponseEntity<ResumeAnalysisStatusDto> resp = controller(svc).status(user, resumeId);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("NOT_ANALYZED", resp.getBody().status());
    }

    @Test
    void analysisReturns404WhenNoAnalysisPresent() {
        ResumeIntelligenceCenterService svc = mock(ResumeIntelligenceCenterService.class);
        when(svc.isEnabled()).thenReturn(true);
        when(svc.analysis(user.userId(), resumeId)).thenReturn(Optional.empty());

        assertEquals(HttpStatus.NOT_FOUND, controller(svc).analysis(user, resumeId).getStatusCode());
    }

    @Test
    void historyDelegatesWhenEnabled() {
        ResumeIntelligenceCenterService svc = mock(ResumeIntelligenceCenterService.class);
        when(svc.isEnabled()).thenReturn(true);
        when(svc.history(user.userId(), resumeId)).thenReturn(List.of());

        assertEquals(HttpStatus.OK, controller(svc).history(user, resumeId).getStatusCode());
    }
}
