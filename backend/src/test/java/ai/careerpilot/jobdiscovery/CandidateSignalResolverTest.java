package ai.careerpilot.jobdiscovery;

import ai.careerpilot.api.dto.CandidatePreferencesDto;
import ai.careerpilot.domain.CandidateProfile;
import ai.careerpilot.domain.WorkflowRun;
import ai.careerpilot.jobdiscovery.CandidateSignalResolver.CandidateMatchSignals;
import ai.careerpilot.repo.CandidateProfileRepository;
import ai.careerpilot.repo.WorkflowRunRepository;
import ai.careerpilot.service.CandidatePreferencesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * The Phase-1 single-source-of-truth switch: when {@code profile-source-enabled} is on and a
 * profile exists, signals come from the canonical {@link CandidateProfile} ONLY; otherwise the
 * resolver falls back to the legacy WorkflowRun + preferences path with no behavior change.
 * Pure Mockito — no Spring context, matching the project convention.
 */
class CandidateSignalResolverTest {

    private WorkflowRunRepository runs;
    private CandidateProfileRepository profiles;
    private CandidatePreferencesService preferences;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        runs = mock(WorkflowRunRepository.class);
        profiles = mock(CandidateProfileRepository.class);
        preferences = mock(CandidatePreferencesService.class);
    }

    private CandidateProfile profile() {
        return CandidateProfile.builder()
                .userId(userId).resumeId(UUID.randomUUID()).yearsExperience(12)
                .skillsJson("[\"Java\",\"Spring Boot\"]")
                .targetRolesJson("[\"Solution Architect\"]")
                .preferredCountriesJson("[\"Germany\"]")
                .workModesJson("[\"Remote\"]")
                .excludedRolesJson("[\"Sales\"]")
                .visaRequired(true)
                .build();
    }

    private WorkflowRun workflowRun() {
        return WorkflowRun.builder()
                .userId(userId).orgId(UUID.randomUUID()).threadId("t").status("COMPLETED")
                .targetRole("Backend Engineer").atsScore(80)
                .state("{\"extracted_skills\":[\"Java\"],\"target_locations\":[\"Berlin\"]," +
                        "\"candidate_profile\":{\"years_experience\":10}}")
                .build();
    }

    @Test
    void profileSourceOnReadsFromProfileOnly() {
        when(profiles.findByUserId(userId)).thenReturn(Optional.of(profile()));
        var resolver = new CandidateSignalResolver(runs, profiles, preferences, true, false);

        CandidateMatchSignals s = resolver.resolve(userId).orElseThrow();

        assertEquals("PROFILE", s.source());
        assertEquals(List.of("Java", "Spring Boot"), s.skills());
        assertTrue(s.targetRole().contains("Solution Architect"));
        assertEquals(List.of("Sales"), s.excludedRoles());
        assertEquals(12, s.yearsExperience());
        assertTrue(s.preferences().remote());
        assertTrue(s.preferences().visaRequired());
        verifyNoInteractions(runs, preferences);   // the profile is the SOLE source
    }

    @Test
    void profileSourceOffUsesLegacyWorkflowAndPreferences() {
        when(runs.findTop20ByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(workflowRun()));
        when(preferences.get(userId)).thenReturn(new CandidatePreferencesDto(
                List.of(), List.of(), List.of("Tech Lead"), List.of("Marketing"),
                false, false, false, false, false, null, null, null, null));
        var resolver = new CandidateSignalResolver(runs, profiles, preferences, false, false);

        CandidateMatchSignals s = resolver.resolve(userId).orElseThrow();

        assertEquals("WORKFLOW", s.source());
        assertEquals(List.of("Java"), s.skills());
        assertTrue(s.targetRole().contains("Backend Engineer"));
        assertTrue(s.targetRole().contains("Tech Lead"));     // role merged with preferred roles
        assertEquals(List.of("Marketing"), s.excludedRoles()); // exclusions work in legacy mode too
        assertEquals(80, s.atsScore());
        verifyNoInteractions(profiles);
    }

    @Test
    void profileSourceOnButNoProfileFallsBackToLegacy() {
        when(profiles.findByUserId(userId)).thenReturn(Optional.empty());
        when(runs.findTop20ByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(workflowRun()));
        when(preferences.get(userId)).thenReturn(CandidatePreferencesDto.defaults());
        var resolver = new CandidateSignalResolver(runs, profiles, preferences, true, false);

        CandidateMatchSignals s = resolver.resolve(userId).orElseThrow();

        assertEquals("WORKFLOW", s.source(), "no profile yet → safe legacy fallback");
    }

    @Test
    void noProfileAndNoWorkflowReturnsEmpty() {
        when(profiles.findByUserId(userId)).thenReturn(Optional.empty());
        when(runs.findTop20ByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of());
        var resolver = new CandidateSignalResolver(runs, profiles, preferences, true, false);

        assertTrue(resolver.resolve(userId).isEmpty());
    }

    // ── Phase 1.5: resolveLocationSignals (Domestic/International + excluded-role filtering) ──

    @Test
    void singleSourceOnReadsLocationSignalsFromProfileOnly() {
        when(profiles.findByUserId(userId)).thenReturn(Optional.of(profile()));
        var resolver = new CandidateSignalResolver(runs, profiles, preferences, false, true);

        var s = resolver.resolveLocationSignals(userId);

        assertEquals("PROFILE", s.source());
        assertEquals(List.of("Germany"), s.preferredCountries());
        assertEquals(List.of("Sales"), s.excludedRoles());
        verifyNoInteractions(preferences);   // the profile is the SOLE source
    }

    @Test
    void singleSourceOffUsesLegacyPreferences() {
        when(preferences.get(userId)).thenReturn(new CandidatePreferencesDto(
                List.of("Germany"), List.of(), List.of(), List.of("Marketing"),
                false, false, false, false, false, null, null, null, "France"));
        var resolver = new CandidateSignalResolver(runs, profiles, preferences, false, false);

        var s = resolver.resolveLocationSignals(userId);

        assertEquals("PREFERENCES", s.source());
        assertEquals("France", s.homeCountry());
        assertEquals(List.of("Marketing"), s.excludedRoles());
        verifyNoInteractions(profiles);
    }

    @Test
    void singleSourceOnButNoProfileFallsBackToLegacyPreferences() {
        when(profiles.findByUserId(userId)).thenReturn(Optional.empty());
        when(preferences.get(userId)).thenReturn(CandidatePreferencesDto.defaults());
        var resolver = new CandidateSignalResolver(runs, profiles, preferences, false, true);

        var s = resolver.resolveLocationSignals(userId);

        assertEquals("PREFERENCES", s.source(), "no profile yet → safe legacy fallback");
    }
}
