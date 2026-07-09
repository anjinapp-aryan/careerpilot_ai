package ai.careerpilot.review;

import ai.careerpilot.domain.*;
import ai.careerpilot.learning.api.LearningExplainContextService;
import ai.careerpilot.repo.*;
import ai.careerpilot.review.reviewer.*;
import ai.careerpilot.workflow.correlation.WorkflowCorrelationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ApplicationReviewPipelineTest {

    private ApplicationPackageRepository packages;
    private ResumeTailoringRepository tailorings;
    private ResumeAtsAnalysisRepository atsAnalyses;
    private JobRecommendationRepository recommendations;
    private JobRepository jobs;
    private LearningExplainContextService learningContext;
    private ApplicationReviewRepository reviews;
    private ApplicationReviewHistoryRepository reviewHistory;
    private WorkflowCorrelationService correlation;

    private final UUID userId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();
    private final UUID pkgId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        packages = mock(ApplicationPackageRepository.class);
        tailorings = mock(ResumeTailoringRepository.class);
        atsAnalyses = mock(ResumeAtsAnalysisRepository.class);
        recommendations = mock(JobRecommendationRepository.class);
        jobs = mock(JobRepository.class);
        learningContext = mock(LearningExplainContextService.class);
        reviews = mock(ApplicationReviewRepository.class);
        reviewHistory = mock(ApplicationReviewHistoryRepository.class);
        correlation = mock(WorkflowCorrelationService.class);
        when(correlation.start(any(), any(), any(), any())).thenReturn(UUID.randomUUID());
        when(reviews.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(reviews.findByApplicationPackageId(any())).thenReturn(Optional.empty());
        when(learningContext.isEnabled()).thenReturn(false);
    }

    private ApplicationReviewPipeline pipeline(boolean enabled) {
        return new ApplicationReviewPipeline(packages, tailorings, atsAnalyses, recommendations, jobs,
                learningContext, reviews, reviewHistory,
                new ResumeReviewer(true), new AtsReviewer(true), new CompanyFitReviewer(true),
                new LearningReviewer(true), new ConsistencyReviewer(true), new QualityReviewer(true),
                correlation, new ReviewMetrics(), enabled);
    }

    private ApplicationPackage completePackage() {
        return ApplicationPackage.builder().id(pkgId).userId(userId).jobId(jobId)
                .resumeId(UUID.randomUUID()).resumeTailoringId(UUID.randomUUID())
                .atsAnalysisId(UUID.randomUUID()).packageVersion(1)
                .status("ASSEMBLED").validationStatus("READY").build();
    }

    @Test
    void disabledIsNoOp() {
        assertTrue(pipeline(false).review(pkgId, null).isEmpty());
        verifyNoInteractions(packages);
    }

    @Test
    void reviewsCompletePackageAndPersists() {
        ApplicationPackage pkg = completePackage();
        when(packages.findById(pkgId)).thenReturn(Optional.of(pkg));
        when(tailorings.findFirstByUserIdAndJobIdOrderByTailoringVersionDesc(userId, jobId))
                .thenReturn(Optional.of(ResumeTailoring.builder().tailoringVersion(1).atsAfter(85)
                        .improvementScore(10).confidenceScore(new BigDecimal("0.9")).build()));
        ResumeAtsAnalysis ats = ResumeAtsAnalysis.builder().atsScore(88).build();
        when(atsAnalyses.findFirstByUserIdAndJobIdOrderByCreatedAtDesc(userId, jobId)).thenReturn(Optional.of(ats));
        JobRecommendation rec = new JobRecommendation();
        rec.setMatchScore(88);
        rec.setConfidenceLevel("HIGH");
        when(recommendations.findByUserIdAndJobId(userId, jobId)).thenReturn(Optional.of(rec));
        when(jobs.findById(jobId)).thenReturn(Optional.of(new Job()));

        Optional<ApplicationReview> result = pipeline(true).review(pkgId, null);

        assertTrue(result.isPresent());
        assertNotNull(result.get().getVerdict());
        assertNotNull(result.get().getQualityCategory());
        verify(reviewHistory).save(any());
    }

    @Test
    void repositoryFailureRecordsBlockedNeverThrows() {
        when(packages.findById(pkgId)).thenReturn(Optional.of(completePackage()));
        when(tailorings.findFirstByUserIdAndJobIdOrderByTailoringVersionDesc(any(), any()))
                .thenThrow(new RuntimeException("db down"));
        assertDoesNotThrow(() -> pipeline(true).review(pkgId, null));
        verify(reviews, atLeastOnce()).save(any()); // blocked review recorded
    }
}
