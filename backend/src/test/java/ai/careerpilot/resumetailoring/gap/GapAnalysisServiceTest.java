package ai.careerpilot.resumetailoring.gap;

import ai.careerpilot.domain.Job;
import ai.careerpilot.domain.Resume;
import ai.careerpilot.domain.ResumeGapAnalysis;
import ai.careerpilot.domain.ResumeTailoring;
import ai.careerpilot.repo.*;
import ai.careerpilot.resumetailoring.event.GapAnalysisCompletedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Phase 2D.3 — {@link GapAnalysisService} must be deterministic (identical inputs → identical
 * output), non-LLM (no AI dependency exists at all), cache-aware, failure-isolated, and must
 * publish {@link GapAnalysisCompletedEvent} only on success.
 */
class GapAnalysisServiceTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();
    private final UUID tailoringId = UUID.randomUUID();
    private final UUID atsAnalysisId = UUID.randomUUID();

    private ResumeTailoringRepository tailorings;
    private ResumeRepository resumes;
    private JobRepository jobs;
    private JobAiEnrichmentRepository enrichment;
    private CandidateProfileRepository profiles;
    private CandidateProfileVersionRepository profileVersions;
    private CandidateBehaviorProfileRepository behaviorProfiles;
    private ResumeGapAnalysisRepository gapAnalyses;
    private GapAnalysisCache cache;
    private ApplicationEventPublisher events;

    private GapAnalysisService service(boolean enabled) {
        tailorings = mock(ResumeTailoringRepository.class);
        resumes = mock(ResumeRepository.class);
        jobs = mock(JobRepository.class);
        enrichment = mock(JobAiEnrichmentRepository.class);
        profiles = mock(CandidateProfileRepository.class);
        profileVersions = mock(CandidateProfileVersionRepository.class);
        behaviorProfiles = mock(CandidateBehaviorProfileRepository.class);
        gapAnalyses = mock(ResumeGapAnalysisRepository.class);
        cache = mock(GapAnalysisCache.class);
        events = mock(ApplicationEventPublisher.class);

        when(cache.get(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(enrichment.findByJobId(any())).thenReturn(Optional.empty());
        when(profiles.findByUserId(any())).thenReturn(Optional.empty());
        when(profileVersions.findByUserIdOrderByCreatedAtDesc(any())).thenReturn(List.of());
        when(behaviorProfiles.findById(any())).thenReturn(Optional.empty());
        when(resumes.findByUserIdOrderByCreatedAtDesc(any())).thenReturn(List.of());
        when(gapAnalyses.save(any(ResumeGapAnalysis.class))).thenAnswer(inv -> {
            ResumeGapAnalysis g = inv.getArgument(0);
            g.setId(UUID.randomUUID());
            return g;
        });

        return new GapAnalysisService(tailorings, resumes, jobs, enrichment, profiles, profileVersions,
                behaviorProfiles, gapAnalyses, cache, new GapAnalysisMetrics(), events, enabled);
    }

    private void stubTailoringAndJob(String tailoredText, String jobSkills, String jobDescription) {
        ResumeTailoring tailoring = ResumeTailoring.builder().id(tailoringId).userId(userId).jobId(jobId)
                .tailoringVersion(1).tailoredResumeText(tailoredText)
                .status(ResumeTailoring.STATUS_GENERATED).build();
        when(tailorings.findById(tailoringId)).thenReturn(Optional.of(tailoring));
        Job job = Job.builder().id(jobId).title("Platform Engineer").company("Acme")
                .description(jobDescription).skills(jobSkills).build();
        when(jobs.findById(jobId)).thenReturn(Optional.of(job));
    }

    @Test
    void disabledIsACompleteNoOp() {
        GapAnalysisService service = service(false);
        assertFalse(service.isEnabled());
        assertTrue(service.analyze(userId, jobId, tailoringId, atsAnalysisId).isEmpty());
        verifyNoInteractions(tailorings, jobs, gapAnalyses, events);
    }

    @Test
    void detectsMissingSkillsAndCloudRequirementsDeterministically() {
        GapAnalysisService service = service(true);
        stubTailoringAndJob("Java and Spring Boot backend engineer resume text.",
                "java,spring,kafka", "We need Java, Spring, Kafka and Kubernetes on AWS.");

        ResumeGapAnalysis result = service.analyze(userId, jobId, tailoringId, atsAnalysisId).orElseThrow();

        assertEquals("kafka", result.getMissingSkills());
        assertTrue(result.getMissingCloud().contains("kubernetes"));
        assertTrue(result.getMissingCloud().contains("aws"));
        assertTrue(result.getGapScore() > 0);
        assertEquals(tailoringId, result.getResumeTailoringId());
        assertEquals(atsAnalysisId, result.getResumeAtsAnalysisId());
    }

    @Test
    void identicalInputsProduceIdenticalOutputRepeatably() {
        GapAnalysisService s1 = service(true);
        stubTailoringAndJob("Java engineer.", "java,terraform", "Java role with terraform.");
        ResumeGapAnalysis first = s1.analyze(userId, jobId, tailoringId, atsAnalysisId).orElseThrow();

        GapAnalysisService s2 = service(true);
        stubTailoringAndJob("Java engineer.", "java,terraform", "Java role with terraform.");
        ResumeGapAnalysis second = s2.analyze(userId, jobId, tailoringId, atsAnalysisId).orElseThrow();

        assertEquals(first.getGapScore(), second.getGapScore());
        assertEquals(first.getMissingSkills(), second.getMissingSkills());
        assertEquals(first.getMissingCloud(), second.getMissingCloud());
    }

    @Test
    void noGapsWhenTheResumeCoversEverythingTheJobNames() {
        GapAnalysisService service = service(true);
        stubTailoringAndJob("Java, Spring, AWS, Kubernetes, leadership and architecture experience.",
                "java,spring", "Java and Spring role. AWS, kubernetes, leadership, architecture.");

        ResumeGapAnalysis result = service.analyze(userId, jobId, tailoringId, null).orElseThrow();

        assertNull(result.getMissingSkills());
        assertNull(result.getMissingCloud());
        assertNull(result.getMissingLeadership());
        assertEquals(0, result.getGapScore());
    }

    @Test
    void cacheHitReturnsExistingRowWithoutRecomputeOrRepublish() {
        GapAnalysisService service = service(true);
        stubTailoringAndJob("Java engineer.", "java", "Java role.");
        UUID cachedId = UUID.randomUUID();
        ResumeGapAnalysis existing = ResumeGapAnalysis.builder().id(cachedId).userId(userId).jobId(jobId)
                .resumeTailoringId(tailoringId).gapScore(10).build();
        when(cache.get(userId, jobId, tailoringId, atsAnalysisId)).thenReturn(Optional.of(cachedId));
        when(gapAnalyses.findById(cachedId)).thenReturn(Optional.of(existing));

        ResumeGapAnalysis result = service.analyze(userId, jobId, tailoringId, atsAnalysisId).orElseThrow();

        assertEquals(cachedId, result.getId());
        verify(gapAnalyses, never()).save(any());
        verifyNoInteractions(events);
    }

    @Test
    void missingTailoringIsHandledWithoutThrowingAndNothingIsPublished() {
        GapAnalysisService service = service(true);
        when(tailorings.findById(tailoringId)).thenReturn(Optional.empty());
        when(jobs.findById(jobId)).thenReturn(Optional.empty());

        assertTrue(service.analyze(userId, jobId, tailoringId, atsAnalysisId).isEmpty());
        verify(gapAnalyses, never()).save(any());
        verifyNoInteractions(events);
    }

    @Test
    void publishesGapAnalysisCompletedEventOnSuccess() {
        GapAnalysisService service = service(true);
        stubTailoringAndJob("Java engineer.", "java,go", "Java and Go role.");

        ResumeGapAnalysis result = service.analyze(userId, jobId, tailoringId, atsAnalysisId).orElseThrow();

        verify(events).publishEvent(new GapAnalysisCompletedEvent(userId, jobId, tailoringId,
                atsAnalysisId, result.getId()));
    }

    @Test
    void originalResumeAndProfileCountAsEvidenceNotJustTheTailoredText() {
        GapAnalysisService service = service(true);
        stubTailoringAndJob("Short tailored text without the keyword.", "java,rust", "Java and Rust role.");
        Resume original = Resume.builder().id(UUID.randomUUID()).userId(userId)
                .parsedText("Extensive Rust systems programming experience.").createdAt(Instant.now()).build();
        when(resumes.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(original));

        ResumeGapAnalysis result = service.analyze(userId, jobId, tailoringId, null).orElseThrow();

        // rust is evidenced by the ORIGINAL resume, so only java's absence could be flagged — and
        // java is missing from all evidence here.
        assertEquals("java", result.getMissingSkills());
    }
}
