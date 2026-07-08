package ai.careerpilot.resumetailoring.service;

import ai.careerpilot.ai.AiGatewayService;
import ai.careerpilot.domain.*;
import ai.careerpilot.learning.recommendation.AdaptiveRecommendationEngine;
import ai.careerpilot.learning.resume.AdaptiveResumeEngine;
import ai.careerpilot.learning.resume.LearningResumeOrdering;
import ai.careerpilot.repo.*;
import ai.careerpilot.resumetailoring.audit.ResumeTailoringAuditService;
import ai.careerpilot.resumetailoring.cache.ResumeTailoringCache;
import ai.careerpilot.resumetailoring.cache.ResumeTailoringCacheMetrics;
import ai.careerpilot.resumetailoring.llm.ResumeTailoringPromptBuilder;
import ai.careerpilot.resumetailoring.llm.ResumeTailoringValidator;
import ai.careerpilot.resumetailoring.scoring.ResumeImprovementCalculator;
import ai.careerpilot.resumetailoring.version.ResumeVersionManager;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Phase 2D.1 — end-to-end through {@link ResumeTailoringService}: disabled is a full no-op,
 * generation persists a new immutable version and never mutates the original resume, a cache hit
 * returns the existing row without calling the LLM again, and a validation failure is recorded as
 * an audit rejection without ever persisting a {@link ResumeTailoring} row.
 */
class ResumeTailoringServiceTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();
    private final UUID resumeId = UUID.randomUUID();

    private ResumeRepository resumes;
    private JobRepository jobs;
    private CandidateProfileRepository profiles;
    private CandidateProfileVersionRepository profileVersions;
    private CandidateBehaviorProfileRepository behaviorProfiles;
    private JobAiEnrichmentRepository enrichment;
    private JobRecommendationExplanationRepository explanations;
    private RecommendationAuditRepository recommendationAudit;
    private ResumeTailoringRepository tailorings;
    private AiGatewayService ai;
    private ResumeTailoringCache cache;
    private ResumeTailoringAuditService auditService;

    private ResumeTailoringService service(boolean enabled) {
        resumes = mock(ResumeRepository.class);
        jobs = mock(JobRepository.class);
        profiles = mock(CandidateProfileRepository.class);
        profileVersions = mock(CandidateProfileVersionRepository.class);
        behaviorProfiles = mock(CandidateBehaviorProfileRepository.class);
        enrichment = mock(JobAiEnrichmentRepository.class);
        explanations = mock(JobRecommendationExplanationRepository.class);
        recommendationAudit = mock(RecommendationAuditRepository.class);
        tailorings = mock(ResumeTailoringRepository.class);
        ai = mock(AiGatewayService.class);
        cache = mock(ResumeTailoringCache.class);
        auditService = mock(ResumeTailoringAuditService.class);

        when(profiles.findByUserId(any())).thenReturn(Optional.empty());
        when(profileVersions.findByUserIdOrderByCreatedAtDesc(any())).thenReturn(List.of());
        when(behaviorProfiles.findById(any())).thenReturn(Optional.empty());
        when(enrichment.findByJobId(any())).thenReturn(Optional.empty());
        when(explanations.findByUserIdAndJobId(any(), any())).thenReturn(Optional.empty());
        when(recommendationAudit.findFirstByUserIdAndJobIdOrderByCreatedAtDesc(any(), any())).thenReturn(Optional.empty());
        when(cache.get(any(), any(), any(), any(), any())).thenReturn(Optional.empty());
        when(tailorings.save(any(ResumeTailoring.class))).thenAnswer(inv -> {
            ResumeTailoring t = inv.getArgument(0);
            t.setId(UUID.randomUUID());
            return t;
        });

        LearningResumeOrdering learningOrdering = new LearningResumeOrdering(
                new AdaptiveResumeEngine(mock(ResumeLearningRepository.class), false),
                mock(AdaptiveRecommendationEngine.class));
        return new ResumeTailoringService(resumes, jobs, profiles, profileVersions, behaviorProfiles,
                enrichment, explanations, recommendationAudit, tailorings,
                new ResumeTailoringPromptBuilder(), new ResumeTailoringValidator(10, 20000),
                new ResumeImprovementCalculator(), new ResumeVersionManager(tailorings),
                cache, new ResumeTailoringCacheMetrics(), auditService, ai, learningOrdering, enabled, List.of());
    }

    private void stubResumeAndJob(String originalText) {
        Resume resume = Resume.builder().id(resumeId).userId(userId).parsedText(originalText)
                .createdAt(Instant.now()).build();
        when(resumes.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(resume));
        Job job = Job.builder().id(jobId).title("Backend Engineer").company("Acme").description("Java role")
                .createdAt(Instant.now()).build();
        when(jobs.findById(jobId)).thenReturn(Optional.of(job));
    }

    @Test
    void disabledIsACompleteNoOp() {
        ResumeTailoringService service = service(false);
        assertFalse(service.isEnabled());
        assertTrue(service.tailor(userId, jobId, null).isEmpty());
        verifyNoInteractions(resumes, ai);
    }

    @Test
    void generatesAndPersistsANewImmutableVersionWithoutTouchingTheOriginalResume() {
        ResumeTailoringService service = service(true);
        stubResumeAndJob("Backend engineer with 8 years Java and Spring Boot experience.");
        when(ai.chat(anyList(), anyString(), anyList())).thenReturn(
                "Backend engineer tailored for this role. 8 years Java and Spring Boot experience, "
                        + "now emphasizing platform reliability and distributed systems delivery for the team.");

        Optional<ResumeTailoring> result = service.tailor(userId, jobId, null);

        assertTrue(result.isPresent());
        assertEquals(1, result.get().getTailoringVersion());
        assertEquals(ResumeTailoring.STATUS_GENERATED, result.get().getStatus());
        assertEquals(resumeId, result.get().getOriginalResumeId());
        verify(resumes, never()).save(any()); // original resume is never mutated
        verify(tailorings).save(any(ResumeTailoring.class));
    }

    @Test
    void cacheHitReturnsExistingRowWithoutCallingTheLlm() {
        ResumeTailoringService service = service(true);
        stubResumeAndJob("Backend engineer with Java experience.");
        UUID cachedId = UUID.randomUUID();
        ResumeTailoring existing = ResumeTailoring.builder().id(cachedId).userId(userId).jobId(jobId)
                .tailoringVersion(1).tailoredResumeText("cached text").status(ResumeTailoring.STATUS_GENERATED).build();
        when(cache.get(any(), any(), any(), any(), any())).thenReturn(Optional.of(cachedId));
        when(tailorings.findById(cachedId)).thenReturn(Optional.of(existing));

        Optional<ResumeTailoring> result = service.tailor(userId, jobId, null);

        assertEquals(cachedId, result.orElseThrow().getId());
        verifyNoInteractions(ai);
        verify(tailorings, never()).save(any());
    }

    @Test
    void validationFailureIsAuditedAndNeverPersistsATailoringRow() {
        ResumeTailoringService service = service(true);
        stubResumeAndJob("Backend engineer with only Java experience listed here for testing purposes today.");
        // Tailored text claims a certification never declared or present in the original.
        when(ai.chat(anyList(), anyString(), anyList())).thenReturn(
                "Backend engineer. Google Cloud Professional Architect Certified with strong delivery history "
                        + "across several distributed systems and cloud platform migrations for enterprise clients.");

        Optional<ResumeTailoring> result = service.tailor(userId, jobId, null);

        assertTrue(result.isEmpty());
        verify(tailorings, never()).save(any());
        verify(auditService).record(eq(userId), eq(jobId), isNull(), isNull(), any(), any(), isNull(),
                eq(ResumeTailoringAuditEntry.OUTCOME_VALIDATION_REJECTED), anyString());
    }

    @Test
    void missingResumeOrJobIsHandledWithoutThrowing() {
        ResumeTailoringService service = service(true);
        when(resumes.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of());
        when(jobs.findById(jobId)).thenReturn(Optional.empty());

        assertTrue(service.tailor(userId, jobId, null).isEmpty());
        verify(auditService).record(eq(userId), eq(jobId), isNull(), isNull(), isNull(), any(), isNull(),
                eq(ResumeTailoringAuditEntry.OUTCOME_ERROR), anyString());
    }

    @Test
    void rebuildBypassesCacheEvenWhenAFreshEntryExists() {
        ResumeTailoringService service = service(true);
        stubResumeAndJob("Backend engineer with Java experience described here in sufficient detail for testing.");
        when(ai.chat(anyList(), anyString(), anyList())).thenReturn(
                "Backend engineer tailored resume text with Java experience, reorganized for this specific "
                        + "role and emphasizing the most relevant delivery history for the hiring team.");
        // Even though the cache reports a hit, rebuild() must ignore it.
        when(cache.get(any(), any(), any(), any(), any())).thenReturn(Optional.of(UUID.randomUUID()));

        Optional<ResumeTailoring> result = service.rebuild(userId, jobId);

        assertTrue(result.isPresent());
        verify(ai).chat(anyList(), anyString(), anyList());
        verify(tailorings).save(any(ResumeTailoring.class));
    }
}
