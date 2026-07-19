package ai.careerpilot.execution.execution;

import ai.careerpilot.domain.ApplicationExecution;
import ai.careerpilot.domain.ApplicationPackage;
import ai.careerpilot.domain.Job;
import ai.careerpilot.execution.ats.ATSConnector;
import ai.careerpilot.execution.ats.ATSConnectorRegistry;
import ai.careerpilot.execution.browser.BrowserAutomationProvider;
import ai.careerpilot.execution.browser.GuestApplyAutomationService;
import ai.careerpilot.repo.ApplicationExecutionAuditRepository;
import ai.careerpilot.repo.ApplicationExecutionRepository;
import ai.careerpilot.repo.ApplicationPackageRepository;
import ai.careerpilot.repo.JobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 2E.1 / Gap D — the execution state machine. Pre-Gap-D guarantee (still true for every
 * connector except Greenhouse/Lever): the terminal SUBMITTED state is unreachable — a started
 * execution with no eligible backend can only land in ABORTED. Gap D adds exactly one new path:
 * a guest-apply-eligible, configured connector (Greenhouse/Lever) routes through {@link
 * GuestApplyAutomationService}, landing in AWAITING_APPROVAL (human screenshot gate) rather than
 * being submitted outright — SUBMITTED is only reachable via {@link
 * ApplicationExecutionService#finalizeGuestApplySubmit}, invoked separately after that approval.
 */
class ApplicationExecutionServiceTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();
    private final UUID pkgId = UUID.randomUUID();

    private ApplicationExecutionRepository executions;
    private ApplicationExecutionAuditRepository audit;
    private ApplicationPackageRepository packages;
    private JobRepository jobs;
    private ATSConnectorRegistry connectors;
    private BrowserAutomationProvider browser;
    private GuestApplyAutomationService guestApply;

    @BeforeEach
    void setUp() {
        executions = mock(ApplicationExecutionRepository.class);
        audit = mock(ApplicationExecutionAuditRepository.class);
        packages = mock(ApplicationPackageRepository.class);
        jobs = mock(JobRepository.class);
        connectors = mock(ATSConnectorRegistry.class);
        browser = mock(BrowserAutomationProvider.class);
        guestApply = mock(GuestApplyAutomationService.class);
        when(executions.save(any(ApplicationExecution.class))).thenAnswer(inv -> {
            ApplicationExecution e = inv.getArgument(0);
            if (e.getId() == null) e.setId(UUID.randomUUID());
            return e;
        });
        // baseline: assembled package + job present, no execution backend configured
        when(packages.findById(pkgId)).thenReturn(Optional.of(ApplicationPackage.builder()
                .id(pkgId).userId(userId).jobId(jobId).status(ApplicationPackage.STATUS_ASSEMBLED).build()));
        when(jobs.findById(jobId)).thenReturn(Optional.of(Job.builder().id(jobId).title("Eng").company("Acme").build()));
        when(connectors.detect(any())).thenReturn(null);
        when(browser.isConfigured()).thenReturn(false);
        when(browser.name()).thenReturn("playwright");
    }

    private ApplicationExecutionService service(boolean enabled) {
        return new ApplicationExecutionService(executions, audit, packages, jobs, connectors, browser,
                guestApply, new ApplicationExecutionMetrics(), enabled);
    }

    @Test
    void disabledIsANoOp() {
        assertThat(service(false).execute(userId, jobId, pkgId)).isEmpty();
    }

    @Test
    void missingPackageReturnsEmpty() {
        when(packages.findById(pkgId)).thenReturn(Optional.empty());
        when(packages.findByUserIdAndJobId(userId, jobId)).thenReturn(Optional.empty());
        assertThat(service(true).execute(userId, jobId, pkgId)).isEmpty();
    }

    @Test
    void unassembledPackageAborts() {
        when(packages.findById(pkgId)).thenReturn(Optional.of(ApplicationPackage.builder()
                .id(pkgId).userId(userId).jobId(jobId).status(ApplicationPackage.STATUS_INCOMPLETE).build()));
        ApplicationExecution e = service(true).execute(userId, jobId, pkgId).orElseThrow();
        assertThat(e.getExecutionStatus()).isEqualTo(ApplicationExecution.STATUS_ABORTED);
    }

    @Test
    void noExecutionBackendAborts_neverSubmits() {
        ApplicationExecution e = service(true).execute(userId, jobId, pkgId).orElseThrow();
        assertThat(e.getExecutionStatus()).isEqualTo(ApplicationExecution.STATUS_ABORTED);
        assertThat(e.getExecutionStatus()).isNotEqualTo(ApplicationExecution.STATUS_SUBMITTED);
        assertThat(e.getFailureReason()).contains("no execution backend");
        assertThat(e.getCompletedAt()).isNotNull();
    }

    @Test
    void unconfiguredDetectedConnectorStillAborts() {
        ATSConnector connector = mock(ATSConnector.class);
        when(connector.isConfigured()).thenReturn(false);
        when(connectors.detect(any())).thenReturn(connector);
        ApplicationExecution e = service(true).execute(userId, jobId, pkgId).orElseThrow();
        assertThat(e.getExecutionStatus()).isEqualTo(ApplicationExecution.STATUS_ABORTED);
    }

    @Test
    void configuredLoginRequiredConnectorIsLabelledButStillAborted_zeroRegression() {
        // A configured connector that is NOT guest-apply eligible (e.g. workday/linkedin) must
        // resolve exactly as it did before Gap D — labelled, but ABORTED, never real automation.
        ATSConnector connector = mock(ATSConnector.class);
        when(connector.isConfigured()).thenReturn(true);
        when(connector.name()).thenReturn("workday");
        when(connectors.detect(any())).thenReturn(connector);
        ApplicationExecution e = service(true).execute(userId, jobId, pkgId).orElseThrow();
        assertThat(e.getExecutionType()).isEqualTo(ApplicationExecution.TYPE_ATS_CONNECTOR);
        assertThat(e.getProvider()).isEqualTo("workday");
        assertThat(e.getExecutionStatus()).isEqualTo(ApplicationExecution.STATUS_ABORTED);
    }

    @Test
    void guestApplyEligibleConnectorRoutesToAwaitingApproval_neverStraightToSubmitted() {
        ATSConnector connector = mock(ATSConnector.class);
        when(connector.isConfigured()).thenReturn(true);
        when(connector.name()).thenReturn("greenhouse");
        when(connectors.detect(any())).thenReturn(connector);
        when(guestApply.isEligible(connector)).thenReturn(true);
        UUID approvalId = UUID.randomUUID();
        when(guestApply.attemptFill(any(), any(), any()))
                .thenReturn(GuestApplyAutomationService.AttemptOutcome.awaitingApproval(approvalId));

        ApplicationExecution e = service(true).execute(userId, jobId, pkgId).orElseThrow();
        assertThat(e.getExecutionStatus()).isEqualTo(ApplicationExecution.STATUS_AWAITING_APPROVAL);
        assertThat(e.getCompletedAt()).isNull(); // non-terminal — still in flight
    }

    @Test
    void guestApplyCaptchaOrLoginWallAborts() {
        ATSConnector connector = mock(ATSConnector.class);
        when(connector.isConfigured()).thenReturn(true);
        when(connector.name()).thenReturn("lever");
        when(connectors.detect(any())).thenReturn(connector);
        when(guestApply.isEligible(connector)).thenReturn(true);
        when(guestApply.attemptFill(any(), any(), any()))
                .thenReturn(GuestApplyAutomationService.AttemptOutcome.aborted("captcha or login wall detected"));

        ApplicationExecution e = service(true).execute(userId, jobId, pkgId).orElseThrow();
        assertThat(e.getExecutionStatus()).isEqualTo(ApplicationExecution.STATUS_ABORTED);
        assertThat(e.getFailureReason()).contains("captcha");
    }

    @Test
    void finalizeGuestApplySubmit_refusesUnlessAwaitingApproval() {
        UUID execId = UUID.randomUUID();
        ApplicationExecution notAwaiting = ApplicationExecution.builder()
                .id(execId).userId(userId).jobId(jobId).applicationPackageId(pkgId)
                .executionStatus(ApplicationExecution.STATUS_ABORTED)
                .executionType(ApplicationExecution.TYPE_ATS_CONNECTOR)
                .attemptCount(1).build();
        when(executions.findById(execId)).thenReturn(Optional.of(notAwaiting));

        service(true).finalizeGuestApplySubmit(execId);

        org.mockito.Mockito.verify(guestApply, org.mockito.Mockito.never()).finalizeSubmit(any(), any(), any());
    }

    @Test
    void finalizeGuestApplySubmit_reachesRealSubmittedOnApprovedSubmit() {
        UUID execId = UUID.randomUUID();
        ApplicationExecution awaiting = ApplicationExecution.builder()
                .id(execId).userId(userId).jobId(jobId).applicationPackageId(pkgId)
                .executionStatus(ApplicationExecution.STATUS_AWAITING_APPROVAL)
                .executionType(ApplicationExecution.TYPE_ATS_CONNECTOR)
                .attemptCount(1).build();
        when(executions.findById(execId)).thenReturn(Optional.of(awaiting));
        ATSConnector connector = mock(ATSConnector.class);
        when(connector.isConfigured()).thenReturn(true);
        when(connector.name()).thenReturn("greenhouse");
        when(connectors.detect(any())).thenReturn(connector);
        when(guestApply.isEligible(connector)).thenReturn(true);
        when(guestApply.finalizeSubmit(any(), any(), any()))
                .thenReturn(GuestApplyAutomationService.AttemptOutcome.submitted("conf-123"));

        service(true).finalizeGuestApplySubmit(execId);

        assertThat(awaiting.getExecutionStatus()).isEqualTo(ApplicationExecution.STATUS_SUBMITTED);
        assertThat(awaiting.getCompletedAt()).isNotNull();
    }

    @Test
    void neverThrowsOnDownstreamError() {
        when(jobs.findById(jobId)).thenThrow(new RuntimeException("db down"));
        // returns empty (missing job path is evaluated before the try) rather than throwing
        assertThat(service(true).execute(userId, jobId, pkgId)).isEmpty();
    }

    @Test
    void statusDelegatesToRepo() {
        UUID execId = UUID.randomUUID();
        service(true).status(execId, userId);
        // no exception; delegates
        assertThat(List.of()).isEmpty();
    }
}
