package ai.careerpilot.resumetailoring.coverletter;

import ai.careerpilot.ai.AiGatewayProperties;
import ai.careerpilot.ai.AiGatewayService;
import ai.careerpilot.domain.*;
import ai.careerpilot.repo.*;
import ai.careerpilot.resumetailoring.event.CoverLetterCompletedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Phase 2D.5 — {@link CoverLetterService}: disabled no-op, v1.N versioning (head + immutable
 * version row + audit), validation rejection never persists, cache hit never calls the LLM,
 * LLM failure never throws, event published only on success.
 */
class CoverLetterServiceTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();
    private final UUID tailoringId = UUID.randomUUID();

    private ResumeTailoringRepository tailorings;
    private ResumeRepository resumes;
    private JobRepository jobs;
    private ApplicationRepository applications;
    private CandidateProfileRepository profiles;
    private CandidateProfileVersionRepository profileVersions;
    private CandidateBehaviorProfileRepository behaviorProfiles;
    private CoverLetterRepository coverLetters;
    private CoverLetterVersionRepository versions;
    private CoverLetterAuditRepository audit;
    private CoverLetterCache cache;
    private AiGatewayService ai;
    private ApplicationEventPublisher events;

    private CoverLetterService service(boolean enabled) {
        tailorings = mock(ResumeTailoringRepository.class);
        resumes = mock(ResumeRepository.class);
        jobs = mock(JobRepository.class);
        applications = mock(ApplicationRepository.class);
        profiles = mock(CandidateProfileRepository.class);
        profileVersions = mock(CandidateProfileVersionRepository.class);
        behaviorProfiles = mock(CandidateBehaviorProfileRepository.class);
        coverLetters = mock(CoverLetterRepository.class);
        versions = mock(CoverLetterVersionRepository.class);
        audit = mock(CoverLetterAuditRepository.class);
        cache = mock(CoverLetterCache.class);
        ai = mock(AiGatewayService.class);
        events = mock(ApplicationEventPublisher.class);

        when(cache.get(any(), any(), any())).thenReturn(Optional.empty());
        when(profiles.findByUserId(any())).thenReturn(Optional.empty());
        when(profileVersions.findByUserIdOrderByCreatedAtDesc(any())).thenReturn(List.of());
        when(behaviorProfiles.findById(any())).thenReturn(Optional.empty());
        when(resumes.findByUserIdOrderByCreatedAtDesc(any())).thenReturn(List.of());
        when(applications.findFirstByUserIdAndJobIdOrderByCreatedAtDesc(any(), any())).thenReturn(Optional.empty());
        when(coverLetters.findByUserIdAndJobId(any(), any())).thenReturn(Optional.empty());
        when(coverLetters.save(any(CoverLetter.class))).thenAnswer(inv -> {
            CoverLetter c = inv.getArgument(0);
            if (c.getId() == null) c.setId(UUID.randomUUID());
            return c;
        });
        when(versions.save(any(CoverLetterVersion.class))).thenAnswer(inv -> inv.getArgument(0));

        return new CoverLetterService(tailorings, resumes, jobs, applications, profiles, profileVersions,
                behaviorProfiles, coverLetters, versions, audit, new CoverLetterValidator(100, 6000),
                cache, new CoverLetterMetrics(), ai, new AiGatewayProperties(), events, enabled);
    }

    private void stubTailoringAndJob() {
        ResumeTailoring tailoring = ResumeTailoring.builder().id(tailoringId).userId(userId).jobId(jobId)
                .tailoringVersion(1).tailoredResumeText("Java and Spring Boot engineer, 8 years.")
                .status(ResumeTailoring.STATUS_GENERATED).build();
        when(tailorings.findFirstByUserIdAndJobIdOrderByTailoringVersionDesc(userId, jobId))
                .thenReturn(Optional.of(tailoring));
        Job job = Job.builder().id(jobId).title("Backend Engineer").company("Acme")
                .description("Java role").skills("java,spring").build();
        when(jobs.findById(jobId)).thenReturn(Optional.of(job));
    }

    private static String groundedLetter() {
        return "Dear Hiring Team at Acme, I am excited to apply for the Backend Engineer role. "
                + "My Java and Spring Boot background maps directly onto your needs, and I would "
                + "welcome the opportunity to bring that experience to Acme. Sincerely, A Candidate.";
    }

    @Test
    void disabledIsACompleteNoOp() {
        CoverLetterService service = service(false);
        assertFalse(service.isEnabled());
        assertTrue(service.generate(userId, jobId, null).isEmpty());
        verifyNoInteractions(tailorings, ai, coverLetters, events);
    }

    @Test
    void generatesPersistsHeadPlusImmutableVersionAndPublishesEvent() {
        CoverLetterService service = service(true);
        stubTailoringAndJob();
        when(ai.chat(anyList(), anyString(), anyList())).thenReturn(groundedLetter());
        when(ai.getLastUsedProvider()).thenReturn("gemini");

        CoverLetter result = service.generate(userId, jobId, null).orElseThrow();

        assertEquals(1, result.getVersion());
        assertEquals("gemini", result.getProvider());
        assertEquals(CoverLetter.STATUS_GENERATED, result.getStatus());
        verify(versions).save(any(CoverLetterVersion.class));
        verify(audit).save(argThat(a -> CoverLetterAuditEntry.OUTCOME_GENERATED.equals(a.getOutcome())));
        verify(events).publishEvent(new CoverLetterCompletedEvent(userId, jobId, tailoringId,
                result.getId(), 1));
    }

    @Test
    void regenerationIncrementsTheVersionToV1Dot2() {
        CoverLetterService service = service(true);
        stubTailoringAndJob();
        CoverLetter existing = CoverLetter.builder().id(UUID.randomUUID()).userId(userId).jobId(jobId)
                .version(1).status(CoverLetter.STATUS_GENERATED).content("old").build();
        when(coverLetters.findByUserIdAndJobId(userId, jobId)).thenReturn(Optional.of(existing));
        when(ai.chat(anyList(), anyString(), anyList())).thenReturn(groundedLetter());
        when(ai.getLastUsedProvider()).thenReturn("gemini");

        CoverLetter result = service.generate(userId, jobId, null).orElseThrow();

        assertEquals(2, result.getVersion());
    }

    @Test
    void validationFailureIsAuditedAndNeverPersists() {
        CoverLetterService service = service(true);
        stubTailoringAndJob();
        // Fabricated credential + grounded-enough length: validator must reject.
        when(ai.chat(anyList(), anyString(), anyList())).thenReturn(
                groundedLetter() + " I am also a Terraform Certified expert.");

        assertTrue(service.generate(userId, jobId, null).isEmpty());
        verify(coverLetters, never()).save(any());
        verify(versions, never()).save(any());
        verify(audit).save(argThat(a -> CoverLetterAuditEntry.OUTCOME_VALIDATION_REJECTED.equals(a.getOutcome())));
        verifyNoInteractions(events);
    }

    @Test
    void cacheHitReturnsExistingWithoutCallingTheLlm() {
        CoverLetterService service = service(true);
        stubTailoringAndJob();
        UUID cachedId = UUID.randomUUID();
        CoverLetter existing = CoverLetter.builder().id(cachedId).userId(userId).jobId(jobId)
                .version(1).status(CoverLetter.STATUS_GENERATED).content("cached").build();
        when(cache.get(userId, jobId, tailoringId)).thenReturn(Optional.of(cachedId));
        when(coverLetters.findById(cachedId)).thenReturn(Optional.of(existing));

        CoverLetter result = service.generate(userId, jobId, null).orElseThrow();

        assertEquals(cachedId, result.getId());
        verifyNoInteractions(ai);
        verify(coverLetters, never()).save(any());
    }

    @Test
    void llmFailureIsHandledWithoutThrowing() {
        CoverLetterService service = service(true);
        stubTailoringAndJob();
        when(ai.chat(anyList(), anyString(), anyList())).thenThrow(new RuntimeException("providers down"));

        assertTrue(service.generate(userId, jobId, null).isEmpty());
        verify(audit).save(argThat(a -> CoverLetterAuditEntry.OUTCOME_ERROR.equals(a.getOutcome())));
        verifyNoInteractions(events);
    }
}
