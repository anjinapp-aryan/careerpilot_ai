package ai.careerpilot.packageintel;

import ai.careerpilot.autopilot.decision.ApplicationDecisionEngine;
import ai.careerpilot.autopilot.research.CompanyResearchEngine;
import ai.careerpilot.domain.ApplicationPackage;
import ai.careerpilot.domain.Job;
import ai.careerpilot.domain.JobRecommendation;
import ai.careerpilot.repo.*;
import ai.careerpilot.resumetailoring.apppackage.ApplicationPackageService;
import ai.careerpilot.workflow.correlation.WorkflowCorrelationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ApplicationPackageIntelligenceServiceTest {

    private ApplicationPackageService packageService;
    private ApplicationPackageRepository packages;
    private ApplicationPackageValidationRepository validations;
    private ApplicationDecisionEngine decisionEngine;
    private CompanyResearchEngine companyResearch;
    private JobRecommendationRepository recommendations;
    private ResumeAtsAnalysisRepository atsAnalyses;
    private JobRepository jobs;
    private WorkflowCorrelationService correlation;

    private final UUID userId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();
    private final UUID pkgId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        packageService = mock(ApplicationPackageService.class);
        packages = mock(ApplicationPackageRepository.class);
        validations = mock(ApplicationPackageValidationRepository.class);
        decisionEngine = mock(ApplicationDecisionEngine.class);
        companyResearch = mock(CompanyResearchEngine.class);
        recommendations = mock(JobRecommendationRepository.class);
        atsAnalyses = mock(ResumeAtsAnalysisRepository.class);
        jobs = mock(JobRepository.class);
        correlation = mock(WorkflowCorrelationService.class);
        when(packages.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(validations.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(correlation.start(any(), any(), any(), any())).thenReturn(UUID.randomUUID());
        when(atsAnalyses.findFirstByUserIdAndJobIdOrderByCreatedAtDesc(any(), any())).thenReturn(Optional.empty());
        when(decisionEngine.latest(any(), any())).thenReturn(Optional.empty());
        when(companyResearch.isEnabled()).thenReturn(false);
    }

    private ApplicationPackageIntelligenceService service(boolean enabled) {
        return new ApplicationPackageIntelligenceService(packageService, packages, mock(ApplicationPackageVersionRepository.class),
                validations, decisionEngine, companyResearch, recommendations, atsAnalyses, jobs,
                new ApplicationPackageValidator(), correlation, new PackageIntelligenceMetrics(),
                mock(org.springframework.context.ApplicationEventPublisher.class), enabled, false);
    }

    private ApplicationPackage completePackage() {
        return ApplicationPackage.builder().id(pkgId).userId(userId).jobId(jobId)
                .resumeId(UUID.randomUUID()).resumeTailoringId(UUID.randomUUID())
                .atsAnalysisId(UUID.randomUUID()).packageVersion(1).status("ASSEMBLED").build();
    }

    @Test
    void disabledGenerateIsNoOp() {
        assertTrue(service(false).generate(userId, jobId).isEmpty());
        verifyNoInteractions(packageService);
    }

    @Test
    void disabledEnrichIsNoOp() {
        assertTrue(service(false).enrichAndValidate(pkgId, null).isEmpty());
        verifyNoInteractions(packages);
    }

    @Test
    void completePackageValidatesReady() {
        ApplicationPackage pkg = completePackage();
        when(packages.findById(pkgId)).thenReturn(Optional.of(pkg));
        JobRecommendation rec = new JobRecommendation();
        rec.setMatchScore(92);
        rec.setScoreBreakdown("{\"learningBoost\":5}");
        when(recommendations.findByUserIdAndJobId(userId, jobId)).thenReturn(Optional.of(rec));
        Job job = new Job();
        job.setTitle("Staff Engineer");
        when(jobs.findById(jobId)).thenReturn(Optional.of(job));

        Optional<ApplicationPackage> result = service(true).enrichAndValidate(pkgId, null);

        assertTrue(result.isPresent());
        assertEquals(PackageValidationStatus.READY.name(), result.get().getValidationStatus());
        assertEquals("STRONG", result.get().getRecommendationStrength());
        assertEquals(5, result.get().getLearningBoost());
        verify(validations).save(any());
    }

    @Test
    void missingRecommendationBlocks() {
        ApplicationPackage pkg = completePackage();
        when(packages.findById(pkgId)).thenReturn(Optional.of(pkg));
        when(recommendations.findByUserIdAndJobId(userId, jobId)).thenReturn(Optional.empty());
        when(jobs.findById(jobId)).thenReturn(Optional.empty());

        Optional<ApplicationPackage> result = service(true).enrichAndValidate(pkgId, null);
        assertTrue(result.isPresent());
        assertEquals(PackageValidationStatus.BLOCKED.name(), result.get().getValidationStatus());
    }

    @Test
    void repositoryFailureNeverThrows() {
        when(packages.findById(pkgId)).thenReturn(Optional.of(completePackage()));
        when(recommendations.findByUserIdAndJobId(any(), any())).thenThrow(new RuntimeException("db down"));
        // must not throw — returns the package unchanged and records a blocked validation.
        assertDoesNotThrow(() -> service(true).enrichAndValidate(pkgId, null));
    }
}
