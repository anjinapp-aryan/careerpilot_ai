package ai.careerpilot.resumetailoring.ats;

import ai.careerpilot.ai.AiGatewayProperties;
import ai.careerpilot.ai.AiGatewayService;
import ai.careerpilot.domain.Job;
import ai.careerpilot.domain.ResumeAtsAnalysis;
import ai.careerpilot.domain.ResumeTailoring;
import ai.careerpilot.learning.recommendation.AdaptiveRecommendationEngine;
import ai.careerpilot.learning.resume.AdaptiveResumeEngine;
import ai.careerpilot.learning.resume.LearningResumeOrdering;
import ai.careerpilot.repo.JobRepository;
import ai.careerpilot.repo.ResumeAtsAnalysisRepository;
import ai.careerpilot.repo.ResumeLearningRepository;
import ai.careerpilot.repo.ResumeTailoringRepository;
import ai.careerpilot.resumetailoring.scoring.ResumeImprovementCalculator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Phase 2D.2 — {@link AtsOptimizationService} analyzes the latest tailored resume against its
 * job: deterministic score (reusing {@link ResumeImprovementCalculator} unchanged) + LLM-parsed
 * matched/missing keywords and suggestions. Disabled is a full no-op; missing tailoring/job or an
 * LLM failure is handled without throwing and never persists a row.
 */
class AtsOptimizationServiceTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();
    private final UUID tailoringId = UUID.randomUUID();

    private ResumeTailoringRepository tailorings;
    private JobRepository jobs;
    private ResumeAtsAnalysisRepository analyses;
    private AiGatewayService ai;
    private AiGatewayProperties aiProps;

    private AtsOptimizationService service(boolean enabled) {
        tailorings = mock(ResumeTailoringRepository.class);
        jobs = mock(JobRepository.class);
        analyses = mock(ResumeAtsAnalysisRepository.class);
        ai = mock(AiGatewayService.class);
        aiProps = new AiGatewayProperties();

        when(analyses.save(any(ResumeAtsAnalysis.class))).thenAnswer(inv -> {
            ResumeAtsAnalysis a = inv.getArgument(0);
            a.setId(UUID.randomUUID());
            return a;
        });

        LearningResumeOrdering learningOrdering = new LearningResumeOrdering(
                new AdaptiveResumeEngine(mock(ResumeLearningRepository.class), false),
                mock(AdaptiveRecommendationEngine.class));
        return new AtsOptimizationService(tailorings, jobs, analyses, new ResumeImprovementCalculator(),
                ai, aiProps, new AtsOptimizationMetrics(), learningOrdering, enabled);
    }

    private void stubTailoringAndJob(String tailoredText) {
        ResumeTailoring tailoring = ResumeTailoring.builder().id(tailoringId).userId(userId).jobId(jobId)
                .tailoringVersion(1).tailoredResumeText(tailoredText).status(ResumeTailoring.STATUS_GENERATED).build();
        when(tailorings.findFirstByUserIdAndJobIdOrderByTailoringVersionDesc(userId, jobId))
                .thenReturn(Optional.of(tailoring));
        Job job = Job.builder().id(jobId).title("Backend Engineer").company("Acme")
                .description("Java role").skills("java,spring,aws").build();
        when(jobs.findById(jobId)).thenReturn(Optional.of(job));
    }

    @Test
    void disabledIsACompleteNoOp() {
        AtsOptimizationService service = service(false);
        assertFalse(service.isEnabled());
        assertTrue(service.analyze(userId, jobId).isEmpty());
        verifyNoInteractions(tailorings, jobs, ai);
    }

    @Test
    void analyzesTheLatestTailoredResumeAndPersistsAnAnalysis() {
        AtsOptimizationService service = service(true);
        stubTailoringAndJob("Backend engineer with Java, Spring, and AWS experience.");
        when(ai.chat(anyList(), anyString(), anyList())).thenReturn(
                "{\"matchedKeywords\":[\"java\",\"spring\"],\"missingKeywords\":[\"aws\"],\"suggestions\":[\"Add AWS keyword\"]}");
        when(ai.getLastUsedProvider()).thenReturn("gemini");

        Optional<ResumeAtsAnalysis> result = service.analyze(userId, jobId);

        assertTrue(result.isPresent());
        assertEquals(tailoringId, result.get().getResumeTailoringId());
        assertEquals(ResumeAtsAnalysis.STATUS_GENERATED, result.get().getStatus());
        assertNotNull(result.get().getAtsScore());
        assertEquals("gemini", result.get().getModelUsed());
        verify(analyses).save(any(ResumeAtsAnalysis.class));
    }

    @Test
    void missingTailoringOrJobIsHandledWithoutThrowingAndNothingIsPersisted() {
        AtsOptimizationService service = service(true);
        when(tailorings.findFirstByUserIdAndJobIdOrderByTailoringVersionDesc(userId, jobId)).thenReturn(Optional.empty());
        when(jobs.findById(jobId)).thenReturn(Optional.empty());

        assertTrue(service.analyze(userId, jobId).isEmpty());
        verifyNoInteractions(ai);
        verify(analyses, never()).save(any());
    }

    @Test
    void llmFailureIsHandledWithoutThrowingAndNothingIsPersisted() {
        AtsOptimizationService service = service(true);
        stubTailoringAndJob("Backend engineer with Java experience.");
        when(ai.chat(anyList(), anyString(), anyList())).thenThrow(new RuntimeException("all providers down"));

        assertTrue(service.analyze(userId, jobId).isEmpty());
        verify(analyses, never()).save(any());
    }

    @Test
    void toleratesMarkdownFencedJsonAndMissingKeys() {
        AtsOptimizationService service = service(true);
        stubTailoringAndJob("Backend engineer with Java experience.");
        when(ai.chat(anyList(), anyString(), anyList())).thenReturn(
                "```json\n{\"matchedKeywords\":[\"java\"]}\n```");
        when(ai.getLastUsedProvider()).thenReturn("deepseek");

        Optional<ResumeAtsAnalysis> result = service.analyze(userId, jobId);

        assertTrue(result.isPresent());
        assertEquals("java", result.get().getMatchedKeywords());
        assertNull(result.get().getMissingKeywords());
        assertNull(result.get().getSuggestions());
    }

    @Test
    void usesTheAtsOptimizationRoutingListAsPreferredProviders() {
        AiGatewayProperties props = new AiGatewayProperties();
        props.setRouting(java.util.Map.of("atsOptimization", List.of("gemini", "deepseek")));
        tailorings = mock(ResumeTailoringRepository.class);
        jobs = mock(JobRepository.class);
        analyses = mock(ResumeAtsAnalysisRepository.class);
        ai = mock(AiGatewayService.class);
        when(analyses.save(any(ResumeAtsAnalysis.class))).thenAnswer(inv -> {
            ResumeAtsAnalysis a = inv.getArgument(0);
            a.setId(UUID.randomUUID());
            return a;
        });
        AtsOptimizationService service = new AtsOptimizationService(tailorings, jobs, analyses,
                new ResumeImprovementCalculator(), ai, props, new AtsOptimizationMetrics(),
                new LearningResumeOrdering(new AdaptiveResumeEngine(mock(ResumeLearningRepository.class), false),
                        mock(AdaptiveRecommendationEngine.class)),
                true);
        stubTailoringAndJob("Backend engineer with Java experience.");
        when(ai.chat(anyList(), anyString(), anyList())).thenReturn("{}");

        service.analyze(userId, jobId);

        verify(ai).chat(anyList(), anyString(), eq(List.of("gemini", "deepseek")));
    }
}
