package ai.careerpilot.resumetailoring.apppackage;

import ai.careerpilot.domain.*;
import ai.careerpilot.repo.*;
import ai.careerpilot.resumetailoring.event.ApplicationPackageReadyEvent;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/**
 * Phase 2D.6 — {@link ApplicationPackageService}: reference-based assembly with full lineage,
 * ASSEMBLED vs INCOMPLETE status, package_version increments, event published on every assembly.
 */
class ApplicationPackageServiceTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();
    private final UUID tailoringId = UUID.randomUUID();
    private final UUID resumeId = UUID.randomUUID();
    private final UUID recommendationAuditId = UUID.randomUUID();

    private ResumeTailoringRepository tailorings;
    private CoverLetterRepository coverLetters;
    private ResumeAtsAnalysisRepository atsAnalyses;
    private ResumeGapAnalysisRepository gapAnalyses;
    private ResumeAtsExplanationRepository explanations;
    private ApplicationRepository applications;
    private CandidateProfileVersionRepository profileVersions;
    private CandidateBehaviorProfileRepository behaviorProfiles;
    private RecommendationAuditRepository recommendationAudit;
    private ApplicationPackageRepository packages;
    private ApplicationPackageVersionRepository versions;
    private ApplicationPackageAuditRepository audit;
    private ApplicationPackageCache cache;
    private ApplicationEventPublisher events;

    private ApplicationPackageService service(boolean enabled) {
        tailorings = mock(ResumeTailoringRepository.class);
        coverLetters = mock(CoverLetterRepository.class);
        atsAnalyses = mock(ResumeAtsAnalysisRepository.class);
        gapAnalyses = mock(ResumeGapAnalysisRepository.class);
        explanations = mock(ResumeAtsExplanationRepository.class);
        applications = mock(ApplicationRepository.class);
        profileVersions = mock(CandidateProfileVersionRepository.class);
        behaviorProfiles = mock(CandidateBehaviorProfileRepository.class);
        recommendationAudit = mock(RecommendationAuditRepository.class);
        packages = mock(ApplicationPackageRepository.class);
        versions = mock(ApplicationPackageVersionRepository.class);
        audit = mock(ApplicationPackageAuditRepository.class);
        cache = mock(ApplicationPackageCache.class);
        events = mock(ApplicationEventPublisher.class);

        when(cache.get(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(coverLetters.findByUserIdAndJobId(any(), any())).thenReturn(Optional.empty());
        when(atsAnalyses.findFirstByUserIdAndJobIdOrderByCreatedAtDesc(any(), any())).thenReturn(Optional.empty());
        when(gapAnalyses.findFirstByUserIdAndJobIdOrderByCreatedAtDesc(any(), any())).thenReturn(Optional.empty());
        when(explanations.findFirstByUserIdAndJobIdOrderByCreatedAtDesc(any(), any())).thenReturn(Optional.empty());
        when(applications.findFirstByUserIdAndJobIdOrderByCreatedAtDesc(any(), any())).thenReturn(Optional.empty());
        when(profileVersions.findByUserIdOrderByCreatedAtDesc(any())).thenReturn(List.of());
        when(behaviorProfiles.findById(any())).thenReturn(Optional.empty());
        when(recommendationAudit.findFirstByUserIdAndJobIdOrderByCreatedAtDesc(any(), any())).thenReturn(Optional.empty());
        when(packages.findByUserIdAndJobId(any(), any())).thenReturn(Optional.empty());
        when(packages.save(any(ApplicationPackage.class))).thenAnswer(inv -> {
            ApplicationPackage p = inv.getArgument(0);
            if (p.getId() == null) p.setId(UUID.randomUUID());
            return p;
        });
        when(versions.save(any(ApplicationPackageVersion.class))).thenAnswer(inv -> inv.getArgument(0));

        return new ApplicationPackageService(tailorings, coverLetters, atsAnalyses, gapAnalyses,
                explanations, applications, profileVersions, behaviorProfiles, recommendationAudit,
                packages, versions, audit, cache, new ApplicationPackageMetrics(), events, enabled);
    }

    private ResumeTailoring stubTailoring() {
        ResumeTailoring tailoring = ResumeTailoring.builder().id(tailoringId).userId(userId).jobId(jobId)
                .originalResumeId(resumeId).recommendationAuditId(recommendationAuditId)
                .tailoringVersion(2).tailoredResumeText("text").status(ResumeTailoring.STATUS_GENERATED).build();
        when(tailorings.findFirstByUserIdAndJobIdOrderByTailoringVersionDesc(userId, jobId))
                .thenReturn(Optional.of(tailoring));
        return tailoring;
    }

    @Test
    void disabledIsACompleteNoOp() {
        ApplicationPackageService service = service(false);
        assertFalse(service.isEnabled());
        assertTrue(service.assemble(userId, jobId).isEmpty());
        verifyNoInteractions(tailorings, packages, events);
    }

    @Test
    void assemblesACompletePackageWithFullLineageAndPublishesEvent() {
        ApplicationPackageService service = service(true);
        stubTailoring();
        CoverLetter letter = CoverLetter.builder().id(UUID.randomUUID()).userId(userId).jobId(jobId)
                .version(1).status(CoverLetter.STATUS_GENERATED).content("letter").build();
        when(coverLetters.findByUserIdAndJobId(userId, jobId)).thenReturn(Optional.of(letter));
        ResumeAtsAnalysis ats = ResumeAtsAnalysis.builder().id(UUID.randomUUID()).userId(userId).jobId(jobId)
                .resumeTailoringId(tailoringId).atsScore(80).status(ResumeAtsAnalysis.STATUS_GENERATED).build();
        when(atsAnalyses.findFirstByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId)).thenReturn(Optional.of(ats));
        ResumeGapAnalysis gap = ResumeGapAnalysis.builder().id(UUID.randomUUID()).userId(userId).jobId(jobId)
                .resumeTailoringId(tailoringId).gapScore(20).build();
        when(gapAnalyses.findFirstByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId)).thenReturn(Optional.of(gap));
        ResumeAtsExplanation explanation = ResumeAtsExplanation.builder().id(UUID.randomUUID())
                .userId(userId).jobId(jobId).resumeTailoringId(tailoringId).build();
        when(explanations.findFirstByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId)).thenReturn(Optional.of(explanation));

        ApplicationPackage result = service.assemble(userId, jobId).orElseThrow();

        assertEquals(ApplicationPackage.STATUS_ASSEMBLED, result.getStatus());
        assertEquals(1, result.getPackageVersion());
        assertEquals(tailoringId, result.getResumeTailoringId());
        assertEquals(resumeId, result.getResumeId());
        assertEquals(letter.getId(), result.getCoverLetterId());
        assertEquals(ats.getId(), result.getAtsAnalysisId());
        assertEquals(gap.getId(), result.getGapAnalysisId());
        assertEquals(explanation.getId(), result.getAtsExplanationId());
        assertEquals(recommendationAuditId, result.getRecommendationAuditId());
        assertNotNull(result.getMetadata());
        assertTrue(result.getMetadata().contains("\"resume\""));
        assertTrue(result.getMetadata().contains("v1.2"));
        verify(versions).save(any(ApplicationPackageVersion.class));
        verify(events).publishEvent(new ApplicationPackageReadyEvent(userId, jobId, result.getId(), 1));
    }

    @Test
    void missingOptionalArtifactsYieldIncompleteStatusButStillAssemble() {
        ApplicationPackageService service = service(true);
        stubTailoring();

        ApplicationPackage result = service.assemble(userId, jobId).orElseThrow();

        assertEquals(ApplicationPackage.STATUS_INCOMPLETE, result.getStatus());
        assertNull(result.getCoverLetterId());
        verify(audit).save(argThat(a ->
                ApplicationPackageAuditEntry.OUTCOME_INCOMPLETE.equals(a.getOutcome())
                        && a.getReason().contains("coverLetter")));
        verify(events).publishEvent(any(ApplicationPackageReadyEvent.class));
    }

    @Test
    void noTailoredResumeMeansNoPackageAndNoEvent() {
        ApplicationPackageService service = service(true);
        when(tailorings.findFirstByUserIdAndJobIdOrderByTailoringVersionDesc(userId, jobId))
                .thenReturn(Optional.empty());

        assertTrue(service.assemble(userId, jobId).isEmpty());
        verify(packages, never()).save(any());
        verify(audit).save(argThat(a -> ApplicationPackageAuditEntry.OUTCOME_ERROR.equals(a.getOutcome())));
        verifyNoInteractions(events);
    }

    @Test
    void reassemblyIncrementsThePackageVersion() {
        ApplicationPackageService service = service(true);
        stubTailoring();
        ApplicationPackage existing = ApplicationPackage.builder().id(UUID.randomUUID())
                .userId(userId).jobId(jobId).packageVersion(3)
                .status(ApplicationPackage.STATUS_INCOMPLETE).build();
        when(packages.findByUserIdAndJobId(userId, jobId)).thenReturn(Optional.of(existing));

        ApplicationPackage result = service.assemble(userId, jobId).orElseThrow();

        assertEquals(4, result.getPackageVersion());
    }
}
