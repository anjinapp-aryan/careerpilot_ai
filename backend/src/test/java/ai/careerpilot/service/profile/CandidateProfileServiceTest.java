package ai.careerpilot.service.profile;

import ai.careerpilot.api.dto.CandidatePreferencesDto;
import ai.careerpilot.api.dto.CandidateProfileDto;
import ai.careerpilot.domain.CandidateProfile;
import ai.careerpilot.domain.CandidateProfileVersion;
import ai.careerpilot.domain.Resume;
import ai.careerpilot.repo.CandidateProfileRepository;
import ai.careerpilot.repo.CandidateProfileVersionRepository;
import ai.careerpilot.repo.ResumeRepository;
import ai.careerpilot.service.CandidatePreferencesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Behavioral guards for the profile orchestrator — the two decisions that define Phase 1:
 * resume changes run the LLM; preference-only changes re-merge WITHOUT the LLM. Plus version
 * auditing and failure isolation. Pure Mockito — no Spring context.
 */
class CandidateProfileServiceTest {

    private CandidateProfileRepository profiles;
    private CandidateProfileVersionRepository versions;
    private ResumeRepository resumes;
    private CandidatePreferencesService preferences;
    private CandidateProfileExtractor extractor;
    private CandidateProfileMetrics metrics;
    private CandidateProfileService service;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        profiles = mock(CandidateProfileRepository.class);
        versions = mock(CandidateProfileVersionRepository.class);
        resumes = mock(ResumeRepository.class);
        preferences = mock(CandidatePreferencesService.class);
        extractor = mock(CandidateProfileExtractor.class);
        metrics = new CandidateProfileMetrics();
        service = new CandidateProfileService(profiles, versions, resumes, preferences, extractor, metrics);

        when(preferences.get(any())).thenReturn(CandidatePreferencesDto.defaults());
        when(profiles.save(any())).thenAnswer(inv -> {
            CandidateProfile p = inv.getArgument(0);
            if (p.getId() == null) p.setId(UUID.randomUUID());
            return p;
        });
        when(versions.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private ResumeIntelligence intelligence() {
        return new ResumeIntelligence(12, "Senior Java Developer", "Architect",
                List.of("Java", "Spring Boot"), List.of("Solution Architect"),
                List.of("Finance"), List.of("English"), "Summary.", BigDecimal.valueOf(0.9));
    }

    private Resume resume(UUID id) {
        return Resume.builder().id(id).userId(userId).parsedText("resume text").build();
    }

    @Test
    void resumeChangedRunsLlmAndPersistsProfileAndVersion() {
        UUID resumeId = UUID.randomUUID();
        when(resumes.findById(resumeId)).thenReturn(Optional.of(resume(resumeId)));
        when(profiles.findByUserId(userId)).thenReturn(Optional.empty());
        when(extractor.extract(anyString())).thenReturn(intelligence());

        Optional<CandidateProfileDto> result =
                service.onResumeChanged(userId, resumeId, "RESUME_UPLOADED");

        assertTrue(result.isPresent());
        assertEquals("Senior Java Developer", result.get().currentRole());
        verify(extractor, times(1)).extract(anyString());
        verify(profiles, times(1)).save(any());

        org.mockito.ArgumentCaptor<CandidateProfileVersion> ver =
                org.mockito.ArgumentCaptor.forClass(CandidateProfileVersion.class);
        verify(versions).save(ver.capture());
        assertEquals("RESUME_UPLOADED", ver.getValue().getReason());
        assertNull(ver.getValue().getBeforeJson(), "first generation has no 'before'");
        assertEquals(1L, metrics.snapshot().get("profileGenerationSuccess"));
    }

    @Test
    void preferencesChangedDoesNotRunLlmAndReMergesFromCache() {
        CandidateProfile existing = CandidateProfile.builder()
                .id(UUID.randomUUID()).userId(userId)
                .currentRole("Senior Java Developer").seniorityLevel("Architect")
                .skillsJson("[\"Java\",\"Spring Boot\"]")
                .resumeFingerprint("abc")
                .build();
        when(profiles.findByUserId(userId)).thenReturn(Optional.of(existing));

        Optional<CandidateProfileDto> result = service.onPreferencesChanged(userId);

        assertTrue(result.isPresent());
        assertEquals("Senior Java Developer", result.get().currentRole(), "cached AI fields preserved");
        verify(extractor, never()).extract(anyString());     // ← the key guarantee: no LLM call
        verify(profiles, times(1)).save(any());

        org.mockito.ArgumentCaptor<CandidateProfileVersion> ver =
                org.mockito.ArgumentCaptor.forClass(CandidateProfileVersion.class);
        verify(versions).save(ver.capture());
        assertEquals("PREFERENCES_UPDATED", ver.getValue().getReason());
        assertEquals(1L, metrics.snapshot().get("preferenceUpdates"));
        assertEquals(0L, metrics.snapshot().get("profileGenerationCount"), "no generation attempted");
    }

    @Test
    void preferencesChangedWithNoProfileCreatesPreferencesOnlyProfileWithoutLlm() {
        when(profiles.findByUserId(userId)).thenReturn(Optional.empty());

        Optional<CandidateProfileDto> result = service.onPreferencesChanged(userId);

        assertTrue(result.isPresent());
        assertNull(result.get().currentRole());
        verify(extractor, never()).extract(anyString());
        verify(profiles, times(1)).save(any());
    }

    @Test
    void rebuildForcesLlmAndCountsRebuildRequest() {
        UUID resumeId = UUID.randomUUID();
        when(resumes.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(resume(resumeId)));
        when(profiles.findByUserId(userId)).thenReturn(Optional.empty());
        when(extractor.extract(anyString())).thenReturn(intelligence());

        Optional<CandidateProfileDto> result = service.rebuild(userId);

        assertTrue(result.isPresent());
        verify(extractor, times(1)).extract(anyString());
        assertEquals(1L, metrics.snapshot().get("profileRebuildRequests"));
    }

    @Test
    void extractionFailureIsIsolatedAndLeavesNoProfileWritten() {
        UUID resumeId = UUID.randomUUID();
        when(resumes.findById(resumeId)).thenReturn(Optional.of(resume(resumeId)));
        when(extractor.extract(anyString())).thenThrow(new ProfileExtractionException("bad json"));

        Optional<CandidateProfileDto> result =
                service.onResumeChanged(userId, resumeId, "RESUME_UPLOADED");

        assertTrue(result.isEmpty());
        verify(profiles, never()).save(any());
        verify(versions, never()).save(any());
        assertEquals(1L, metrics.snapshot().get("profileGenerationFailure"));
    }

    @Test
    void backfillUserWithNoResumeReportsSkippedNoResume() {
        when(resumes.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of());

        assertEquals(CandidateProfileService.BackfillOutcome.SKIPPED_NO_RESUME,
                service.backfillUser(userId));
        verify(extractor, never()).extract(anyString());
        verify(profiles, never()).save(any());
    }

    @Test
    void backfillUserWithNoExistingProfileGenerates() {
        UUID resumeId = UUID.randomUUID();
        when(resumes.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(resume(resumeId)));
        when(profiles.findByUserId(userId)).thenReturn(Optional.empty());
        when(extractor.extract(anyString())).thenReturn(intelligence());

        assertEquals(CandidateProfileService.BackfillOutcome.GENERATED,
                service.backfillUser(userId));
        verify(extractor, times(1)).extract(anyString());
        verify(profiles, times(1)).save(any());
    }

    @Test
    void targetRolesUnionPreferredRolesFirstThenAiInferred() {
        UUID resumeId = UUID.randomUUID();
        when(resumes.findById(resumeId)).thenReturn(Optional.of(resume(resumeId)));
        when(profiles.findByUserId(userId)).thenReturn(Optional.empty());
        when(extractor.extract(anyString())).thenReturn(intelligence());
        when(preferences.get(userId)).thenReturn(new CandidatePreferencesDto(
                List.of(), List.of(), List.of("Principal Engineer"), List.of(),
                false, false, false, false, false, null, null, null, null));

        CandidateProfileDto dto = service.onResumeChanged(userId, resumeId, "RESUME_UPLOADED").orElseThrow();

        assertEquals("Principal Engineer", dto.targetRoles().get(0), "user-specified role ranks first");
        assertTrue(dto.targetRoles().contains("Solution Architect"), "AI-inferred role still included");
    }
}
