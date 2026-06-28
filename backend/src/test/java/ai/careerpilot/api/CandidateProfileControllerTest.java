package ai.careerpilot.api;

import ai.careerpilot.api.dto.CandidatePreferencesDto;
import ai.careerpilot.api.dto.CandidateProfileDto;
import ai.careerpilot.security.AuthenticatedUser;
import ai.careerpilot.service.CandidatePreferencesService;
import ai.careerpilot.service.profile.CandidateProfileService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Controller logic in isolation (mocked services) — focuses on the feature-flag gating and the
 * delegate-then-derive contract. No Spring/security slice needed: {@link AuthenticatedUser} is a
 * plain record, matching the project's pure-unit test convention.
 */
class CandidateProfileControllerTest {

    private final AuthenticatedUser user =
            new AuthenticatedUser(UUID.randomUUID(), UUID.randomUUID(), "u@x.io", "OWNER");

    private CandidateProfileDto sampleProfile() {
        return new CandidateProfileDto(UUID.randomUUID(), 12, "Senior Java Developer", "Architect",
                List.of("Java"), List.of("Architect"), List.of(), List.of(), "India", List.of(), List.of(),
                List.of(), false, null, null, null, List.of(), "summary", null, Instant.now());
    }

    // ── Disabled: profile endpoints 404, but preferences still persist ──────────

    @Test
    void disabledFlagReturns404ForGet() {
        CandidateProfileService profile = mock(CandidateProfileService.class);
        CandidateProfileController c =
                new CandidateProfileController(profile, mock(CandidatePreferencesService.class), false);

        assertEquals(HttpStatus.NOT_FOUND, c.get(user).getStatusCode());
        verifyNoInteractions(profile);
    }

    @Test
    void disabledFlagStillPersistsPreferences() {
        CandidatePreferencesService prefs = mock(CandidatePreferencesService.class);
        CandidateProfileController c =
                new CandidateProfileController(mock(CandidateProfileService.class), prefs, false);

        ResponseEntity<?> resp = c.updatePreferences(user, CandidatePreferencesDto.defaults());

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
        verify(prefs, times(1)).save(eq(user.userId()), any());   // ← no data loss when disabled
    }

    // ── Enabled ─────────────────────────────────────────────────────────────────

    @Test
    void enabledReturnsProfileWhenPresent() {
        CandidateProfileService profile = mock(CandidateProfileService.class);
        when(profile.get(user.userId())).thenReturn(Optional.of(sampleProfile()));
        CandidateProfileController c =
                new CandidateProfileController(profile, mock(CandidatePreferencesService.class), true);

        ResponseEntity<CandidateProfileDto> resp = c.get(user);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());
        assertEquals("Senior Java Developer", resp.getBody().currentRole());
    }

    @Test
    void enabledReturns404WhenNoProfileYet() {
        CandidateProfileService profile = mock(CandidateProfileService.class);
        when(profile.get(user.userId())).thenReturn(Optional.empty());
        CandidateProfileController c =
                new CandidateProfileController(profile, mock(CandidatePreferencesService.class), true);

        assertEquals(HttpStatus.NOT_FOUND, c.get(user).getStatusCode());
    }

    @Test
    void updatePreferencesSavesThenReturns202() {
        CandidateProfileService profile = mock(CandidateProfileService.class);
        CandidatePreferencesService prefs = mock(CandidatePreferencesService.class);
        when(profile.get(user.userId())).thenReturn(Optional.of(sampleProfile()));
        CandidateProfileController c = new CandidateProfileController(profile, prefs, true);

        ResponseEntity<CandidateProfileDto> resp = c.updatePreferences(user, CandidatePreferencesDto.defaults());

        verify(prefs, times(1)).save(eq(user.userId()), any());
        assertEquals(HttpStatus.ACCEPTED, resp.getStatusCode());
    }

    @Test
    void rebuildDelegatesAndReturnsProfile() {
        CandidateProfileService profile = mock(CandidateProfileService.class);
        when(profile.rebuild(user.userId())).thenReturn(Optional.of(sampleProfile()));
        CandidateProfileController c =
                new CandidateProfileController(profile, mock(CandidatePreferencesService.class), true);

        ResponseEntity<CandidateProfileDto> resp = c.rebuild(user);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(profile, times(1)).rebuild(user.userId());
    }

    @Test
    void historyReturnsListWhenEnabled() {
        CandidateProfileService profile = mock(CandidateProfileService.class);
        when(profile.history(user.userId())).thenReturn(List.of());
        CandidateProfileController c =
                new CandidateProfileController(profile, mock(CandidatePreferencesService.class), true);

        assertEquals(HttpStatus.OK, c.history(user).getStatusCode());
    }
}
