package ai.careerpilot.resumetailoring.autoapply;

import ai.careerpilot.domain.ApplicationPackage;
import ai.careerpilot.domain.AutoApplyPackage;
import ai.careerpilot.domain.AutoApplyPackageAuditEntry;
import ai.careerpilot.domain.Job;
import ai.careerpilot.repo.AutoApplyPackageAuditRepository;
import ai.careerpilot.repo.AutoApplyPackageRepository;
import ai.careerpilot.repo.ApplicationPackageRepository;
import ai.careerpilot.repo.JobRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/**
 * Phase 2D.7 — {@link AutoApplyPreparationService}: deterministic readiness classification
 * (SAFE_TO_APPLY / REQUIRES_REVIEW / MANUAL_ONLY). Preparation only — the service has no HTTP
 * client and never contacts an external site.
 */
class AutoApplyPreparationServiceTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();
    private final UUID packageId = UUID.randomUUID();

    private ApplicationPackageRepository packages;
    private JobRepository jobs;
    private AutoApplyPackageRepository autoApplyPackages;
    private AutoApplyPackageAuditRepository audit;

    private AutoApplyPreparationService service(boolean enabled) {
        packages = mock(ApplicationPackageRepository.class);
        jobs = mock(JobRepository.class);
        autoApplyPackages = mock(AutoApplyPackageRepository.class);
        audit = mock(AutoApplyPackageAuditRepository.class);

        when(autoApplyPackages.save(any(AutoApplyPackage.class))).thenAnswer(inv -> {
            AutoApplyPackage p = inv.getArgument(0);
            p.setId(UUID.randomUUID());
            return p;
        });

        return new AutoApplyPreparationService(packages, jobs, autoApplyPackages, audit,
                new AutoApplyPreparationMetrics(), enabled);
    }

    private void stubPackage(String status) {
        ApplicationPackage pkg = ApplicationPackage.builder().id(packageId).userId(userId).jobId(jobId)
                .packageVersion(1).status(status).build();
        when(packages.findById(packageId)).thenReturn(Optional.of(pkg));
    }

    private void stubJob(String sourceUrl, String source, String description) {
        Job job = Job.builder().id(jobId).title("Engineer").company("Acme")
                .description(description).sourceUrl(sourceUrl).source(source).build();
        when(jobs.findById(jobId)).thenReturn(Optional.of(job));
    }

    @Test
    void disabledIsACompleteNoOp() {
        AutoApplyPreparationService service = service(false);
        assertFalse(service.isEnabled());
        assertTrue(service.prepare(userId, jobId, packageId).isEmpty());
        verifyNoInteractions(packages, jobs, autoApplyPackages);
    }

    @Test
    void completePackagePlusAggregatorUrlAndNoQuestionnaireIsSafeToApply() {
        AutoApplyPreparationService service = service(true);
        stubPackage(ApplicationPackage.STATUS_ASSEMBLED);
        stubJob("https://remoteok.com/l/12345", "remoteok", "Great Java role.");

        AutoApplyPackage result = service.prepare(userId, jobId, packageId).orElseThrow();

        assertEquals(AutoApplyPackage.STATUS_SAFE_TO_APPLY, result.getStatus());
        assertEquals(AutoApplyPackage.METHOD_EXTERNAL_URL, result.getApplicationMethod());
        assertFalse(result.getRequiresLogin());
        assertFalse(result.getRequiresQuestionnaire());
        assertEquals(100, result.getReadinessScore());
        assertEquals("remoteok", result.getProvider());
    }

    @Test
    void loginRequiringAtsHostDowngradesToRequiresReview() {
        AutoApplyPreparationService service = service(true);
        stubPackage(ApplicationPackage.STATUS_ASSEMBLED);
        stubJob("https://acme.workday.com/careers/123", "adzuna", "Great Java role.");

        AutoApplyPackage result = service.prepare(userId, jobId, packageId).orElseThrow();

        assertEquals(AutoApplyPackage.STATUS_REQUIRES_REVIEW, result.getStatus());
        assertTrue(result.getRequiresLogin());
    }

    @Test
    void questionnaireInThePostingDowngradesToRequiresReview() {
        AutoApplyPreparationService service = service(true);
        stubPackage(ApplicationPackage.STATUS_ASSEMBLED);
        stubJob("https://remoteok.com/l/9", "remoteok", "Java role. Applicants must complete a screening questionnaire.");

        AutoApplyPackage result = service.prepare(userId, jobId, packageId).orElseThrow();

        assertEquals(AutoApplyPackage.STATUS_REQUIRES_REVIEW, result.getStatus());
        assertTrue(result.getRequiresQuestionnaire());
    }

    @Test
    void incompletePackageIsManualOnlyRegardlessOfMethod() {
        AutoApplyPreparationService service = service(true);
        stubPackage(ApplicationPackage.STATUS_INCOMPLETE);
        stubJob("https://remoteok.com/l/9", "remoteok", "Java role.");

        AutoApplyPackage result = service.prepare(userId, jobId, packageId).orElseThrow();

        assertEquals(AutoApplyPackage.STATUS_MANUAL_ONLY, result.getStatus());
        assertTrue(result.getReadinessScore() < 100);
    }

    @Test
    void unknownApplicationMethodIsManualOnly() {
        AutoApplyPreparationService service = service(true);
        stubPackage(ApplicationPackage.STATUS_ASSEMBLED);
        stubJob(null, "manual", "Java role with no link and no contact.");

        AutoApplyPackage result = service.prepare(userId, jobId, packageId).orElseThrow();

        assertEquals(AutoApplyPackage.METHOD_UNKNOWN, result.getApplicationMethod());
        assertEquals(AutoApplyPackage.STATUS_MANUAL_ONLY, result.getStatus());
    }

    @Test
    void missingPackageIsHandledWithoutThrowingAndAudited() {
        AutoApplyPreparationService service = service(true);
        when(packages.findById(packageId)).thenReturn(Optional.empty());
        when(jobs.findById(jobId)).thenReturn(Optional.empty());

        assertTrue(service.prepare(userId, jobId, packageId).isEmpty());
        verify(autoApplyPackages, never()).save(any());
        verify(audit).save(argThat(a -> AutoApplyPackageAuditEntry.OUTCOME_ERROR.equals(a.getOutcome())));
    }
}
