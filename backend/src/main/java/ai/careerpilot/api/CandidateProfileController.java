package ai.careerpilot.api;

import ai.careerpilot.api.dto.CandidatePreferencesDto;
import ai.careerpilot.api.dto.CandidateProfileDto;
import ai.careerpilot.api.dto.CandidateProfileHistoryDto;
import ai.careerpilot.security.AuthenticatedUser;
import ai.careerpilot.service.CandidatePreferencesService;
import ai.careerpilot.service.profile.CandidateProfileService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Read/refresh API for the canonical Candidate Intelligence Profile. User-scoped via the JWT
 * principal (one profile row per user — no cross-tenant exposure). The whole surface is gated
 * by {@code CANDIDATE_PROFILE_ENABLED} (default false): when disabled, profile endpoints return
 * 404 so the feature ships dark, while preference persistence keeps working via the existing
 * {@link CandidatePreferencesService}.
 *
 * The served profile is consumed downstream by job matching (Recommended tab, via
 * {@code CandidateSignalResolver}, gated by {@code JOBS_MATCHING_PROFILE_SOURCE_ENABLED}) and by
 * Domestic/International discovery + excluded-role filtering (gated by
 * {@code PROFILE_SINGLE_SOURCE_ENABLED}, Phase 1.5) — both fall back to legacy sources when off.
 */
@RestController
@RequestMapping("/api/candidate-profile")
public class CandidateProfileController {

    private final CandidateProfileService profileService;
    private final CandidatePreferencesService preferencesService;
    private final boolean enabled;

    public CandidateProfileController(CandidateProfileService profileService,
                                      CandidatePreferencesService preferencesService,
                                      @Value("${candidate.profile.enabled:false}") boolean enabled) {
        this.profileService = profileService;
        this.preferencesService = preferencesService;
        this.enabled = enabled;
    }

    /** Current canonical profile, or 404 when the feature is off / no profile exists yet. */
    @GetMapping
    public ResponseEntity<CandidateProfileDto> get(AuthenticatedUser user) {
        if (!enabled) return ResponseEntity.notFound().build();
        return profileService.get(user.userId())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Update preferences and (re)derive the profile. Preferences are always persisted via the
     * existing service (which publishes an event → async, LLM-free re-merge). Returns 202 with
     * the current profile snapshot; the re-merge completes asynchronously. 404 when disabled.
     */
    @PostMapping("/preferences")
    public ResponseEntity<CandidateProfileDto> updatePreferences(AuthenticatedUser user,
                                                                 @RequestBody CandidatePreferencesDto body) {
        preferencesService.save(user.userId(), body);   // persists + fires PreferencesUpdatedEvent
        if (!enabled) return ResponseEntity.notFound().build();
        return profileService.get(user.userId())
                .map(p -> ResponseEntity.accepted().body(p))
                .orElseGet(() -> ResponseEntity.accepted().build());
    }

    /** Force a full regeneration from the latest resume (synchronous; runs the LLM). */
    @PostMapping("/rebuild")
    public ResponseEntity<CandidateProfileDto> rebuild(AuthenticatedUser user) {
        if (!enabled) return ResponseEntity.notFound().build();
        return profileService.rebuild(user.userId())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Audit trail of previous profile versions (before/after snapshots), newest first. */
    @GetMapping("/history")
    public ResponseEntity<List<CandidateProfileHistoryDto>> history(AuthenticatedUser user) {
        if (!enabled) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(profileService.history(user.userId()));
    }
}
