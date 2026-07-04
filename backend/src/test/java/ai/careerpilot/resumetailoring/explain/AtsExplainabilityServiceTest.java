package ai.careerpilot.resumetailoring.explain;

import ai.careerpilot.domain.*;
import ai.careerpilot.repo.*;
import ai.careerpilot.resumetailoring.event.AtsExplainabilityCompletedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Phase 2D.4 — {@link AtsExplainabilityService} is deterministic and non-LLM by construction (it
 * has no AI dependency at all — the strongest possible "no LLM scoring" guarantee). Matched items
 * come from keyword arithmetic, missing items straight from the persisted gap analysis, and
 * recommendations are fixed templates.
 */
class AtsExplainabilityServiceTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();
    private final UUID tailoringId = UUID.randomUUID();
    private final UUID atsAnalysisId = UUID.randomUUID();
    private final UUID gapAnalysisId = UUID.randomUUID();

    private ResumeTailoringRepository tailorings;
    private ResumeAtsAnalysisRepository atsAnalyses;
    private ResumeGapAnalysisRepository gapAnalyses;
    private JobRepository jobs;
    private JobAiEnrichmentRepository enrichment;
    private CandidateProfileRepository profiles;
    private ResumeAtsExplanationRepository explanations;
    private ApplicationEventPublisher events;

    private AtsExplainabilityService service(boolean enabled) {
        tailorings = mock(ResumeTailoringRepository.class);
        atsAnalyses = mock(ResumeAtsAnalysisRepository.class);
        gapAnalyses = mock(ResumeGapAnalysisRepository.class);
        jobs = mock(JobRepository.class);
        enrichment = mock(JobAiEnrichmentRepository.class);
        profiles = mock(CandidateProfileRepository.class);
        explanations = mock(ResumeAtsExplanationRepository.class);
        events = mock(ApplicationEventPublisher.class);

        when(enrichment.findByJobId(any())).thenReturn(Optional.empty());
        when(profiles.findByUserId(any())).thenReturn(Optional.empty());
        when(explanations.save(any(ResumeAtsExplanation.class))).thenAnswer(inv -> {
            ResumeAtsExplanation e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            return e;
        });

        return new AtsExplainabilityService(tailorings, atsAnalyses, gapAnalyses, jobs, enrichment,
                profiles, explanations, new AtsExplainabilityMetrics(), events, enabled);
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
        AtsExplainabilityService service = service(false);
        assertFalse(service.isEnabled());
        assertTrue(service.explain(userId, jobId, tailoringId, atsAnalysisId, gapAnalysisId).isEmpty());
        verifyNoInteractions(tailorings, explanations, events);
    }

    @Test
    void matchedAndMissingItemsAreDerivedDeterministicallyFromPersistedRows() {
        AtsExplainabilityService service = service(true);
        stubTailoringAndJob("Java and Spring Boot on AWS with strong architecture background.",
                "java,spring,kafka", "Java, Spring, Kafka role. AWS cloud. Architecture. Leadership.");
        ResumeAtsAnalysis ats = ResumeAtsAnalysis.builder().id(atsAnalysisId).userId(userId).jobId(jobId)
                .resumeTailoringId(tailoringId).atsScore(91).status(ResumeAtsAnalysis.STATUS_GENERATED).build();
        when(atsAnalyses.findById(atsAnalysisId)).thenReturn(Optional.of(ats));
        ResumeGapAnalysis gap = ResumeGapAnalysis.builder().id(gapAnalysisId).userId(userId).jobId(jobId)
                .resumeTailoringId(tailoringId).missingSkills("kafka").missingLeadership("leadership")
                .gapScore(25).build();
        when(gapAnalyses.findById(gapAnalysisId)).thenReturn(Optional.of(gap));

        ResumeAtsExplanation result = service.explain(userId, jobId, tailoringId, atsAnalysisId, gapAnalysisId)
                .orElseThrow();

        assertEquals(91, result.getAtsScore());
        assertTrue(result.getMatchedSkills().contains("java"));
        assertTrue(result.getMatchedSkills().contains("spring"));
        assertFalse(result.getMatchedSkills().contains("kafka"));
        assertEquals("aws", result.getMatchedCloud());
        assertEquals("architecture", result.getMatchedArchitecture());
        assertTrue(result.getMissingItems().contains("kafka"));
        assertTrue(result.getMissingItems().contains("leadership"));
        assertTrue(result.getRecommendations().contains("Add kafka experience examples."));
        // Full lineage present -> highest confidence tier.
        assertEquals(0, result.getConfidence().compareTo(new java.math.BigDecimal("0.90")));
    }

    @Test
    void worksWithoutAtsOrGapRowsAtReducedConfidence() {
        AtsExplainabilityService service = service(true);
        stubTailoringAndJob("Java engineer.", "java", "Java role.");
        when(atsAnalyses.findById(any())).thenReturn(Optional.empty());
        when(gapAnalyses.findById(any())).thenReturn(Optional.empty());

        ResumeAtsExplanation result = service.explain(userId, jobId, tailoringId, atsAnalysisId, gapAnalysisId)
                .orElseThrow();

        assertNull(result.getAtsScore());
        assertNull(result.getMissingItems());
        assertEquals(0, result.getConfidence().compareTo(new java.math.BigDecimal("0.50")));
    }

    @Test
    void missingTailoringIsHandledWithoutThrowing() {
        AtsExplainabilityService service = service(true);
        when(tailorings.findById(tailoringId)).thenReturn(Optional.empty());
        when(jobs.findById(jobId)).thenReturn(Optional.empty());

        assertTrue(service.explain(userId, jobId, tailoringId, null, null).isEmpty());
        verify(explanations, never()).save(any());
        verifyNoInteractions(events);
    }

    @Test
    void publishesCompletedEventOnSuccess() {
        AtsExplainabilityService service = service(true);
        stubTailoringAndJob("Java engineer.", "java", "Java role.");
        when(atsAnalyses.findById(any())).thenReturn(Optional.empty());
        when(gapAnalyses.findById(any())).thenReturn(Optional.empty());

        ResumeAtsExplanation result = service.explain(userId, jobId, tailoringId, atsAnalysisId, gapAnalysisId)
                .orElseThrow();

        verify(events).publishEvent(new AtsExplainabilityCompletedEvent(userId, jobId, tailoringId,
                atsAnalysisId, gapAnalysisId, result.getId()));
    }
}
