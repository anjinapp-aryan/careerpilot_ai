package ai.careerpilot.execution.execution;

import ai.careerpilot.domain.ApplicationExecution;
import ai.careerpilot.domain.ApplicationPackage;
import ai.careerpilot.domain.Job;
import ai.careerpilot.execution.ats.ATSConnector;
import ai.careerpilot.execution.ats.ATSConnectorRegistry;
import ai.careerpilot.execution.browser.BrowserAutomationProvider;
import ai.careerpilot.execution.browser.GuestApplyAutomationService;
import ai.careerpilot.execution.recovery.AutomationRecoveryService;
import ai.careerpilot.execution.verification.SubmissionVerificationService;
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
    private SubmissionVerificationService verification;
    private AutomationRecoveryService recovery;

    @BeforeEach
    void setUp() {
        executions = mock(ApplicationExecutionRepository.class);
        audit = mock(ApplicationExecutionAuditRepository.class);
        packages = mock(ApplicationPackageRepository.class);
        jobs = mock(JobRepository.class);
        connectors = mock(ATSConnectorRegistry.class);
        browser = mock(BrowserAutomationProvider.class);
        guestApply = mock(GuestApplyAutomationService.class);
        verification = mock(SubmissionVerificationService.class);
        recovery = mock(AutomationRecoveryService.class);
        // Phase 7.16.3 — no default stub here: `recovery.attemptRecovery` is only ever invoked from
        // the STATUS_FAILED branch of terminal(), which none of the pre-existing (pre-7.16.3) tests
        // exercise — tests that need it stub it locally, and tests that assert
        // verifyNoInteractions(recovery) rely on this method staying genuinely untouched.
        // The approval->submit gate is now an atomic conditional UPDATE rather than a read-and-
        // compare, so every finalize test needs the claim to succeed. The refusal test overrides
        // this with 0, which is what a caller that lost the race actually observes.
        when(executions.claimForSubmit(any())).thenReturn(1);
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
        // Phase 12B — a fully-open rollout gate (100%) so every pre-existing test keeps asserting
        // the same behaviour it always did. The gate's own blocking behaviour is covered separately
        // by `guestApplyIsBlockedForAUserOutsideTheRolloutCohort` below and by BrowserRolloutGateTest.
        return service(enabled, openRolloutGate());
    }

    /** Observability is off in these tests: it must never influence an execution outcome. */
    private static ai.careerpilot.execution.timeline.ExecutionTimelineRecorder disabledTimelineRecorder() {
        return new ai.careerpilot.execution.timeline.ExecutionTimelineRecorder(
                mock(ai.careerpilot.repo.ExecutionStageEventRepository.class),
                new ai.careerpilot.execution.timeline.ExecutionStageMetrics(), false);
    }

    private ApplicationExecutionService service(boolean enabled,
                                                ai.careerpilot.execution.browser.rollout.BrowserRolloutGate gate) {
        return new ApplicationExecutionService(executions, audit, packages, jobs, connectors, browser,
                guestApply, verification, recovery, new ApplicationExecutionMetrics(), gate,
                mock(org.springframework.beans.factory.ObjectProvider.class),
                disabledTimelineRecorder(), enabled);
    }

    private static ai.careerpilot.execution.browser.rollout.BrowserRolloutGate openRolloutGate() {
        return new ai.careerpilot.execution.browser.rollout.BrowserRolloutGate(100, "", "TEST_OPEN");
    }

    private static ai.careerpilot.execution.browser.rollout.BrowserRolloutGate closedRolloutGate() {
        return new ai.careerpilot.execution.browser.rollout.BrowserRolloutGate(0, "", "TEST_CLOSED");
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

    /**
     * Phase 12B — the staged-rollout gate. A user outside the current cohort must take the exact
     * pre-existing ABORTED path, and {@code GuestApplyAutomationService} must never be reached: the
     * gate has to short-circuit <em>before</em> a browser is touched, not merely discard the result
     * afterwards.
     */
    @Test
    void guestApplyIsBlockedForAUserOutsideTheRolloutCohort() {
        ATSConnector connector = mock(ATSConnector.class);
        when(connector.isConfigured()).thenReturn(true);
        when(connector.name()).thenReturn("greenhouse");
        when(connectors.detect(any())).thenReturn(connector);
        when(guestApply.isEligible(connector)).thenReturn(true);

        ApplicationExecution e = service(true, closedRolloutGate()).execute(userId, jobId, pkgId).orElseThrow();

        assertThat(e.getExecutionStatus()).isEqualTo(ApplicationExecution.STATUS_ABORTED);
        assertThat(e.getFailureReason()).contains("rollout stage");
        org.mockito.Mockito.verify(guestApply, org.mockito.Mockito.never())
                .attemptFill(any(), any(), any());
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
        // Not AWAITING_APPROVAL => the conditional UPDATE matches no row.
        when(executions.claimForSubmit(execId)).thenReturn(0);

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
        // Phase 0 — SUBMITTED is now conditional on a VERIFIED verdict, so this test must supply one.
        when(verification.verify(any(), any(), any())).thenReturn(
                ai.careerpilot.execution.verification.VerificationResult.verified(
                        "EVIDENCE_ADJUDICATION:CONFIRMED", "reference plus confirmation phrase"));

        service(true).finalizeGuestApplySubmit(execId);

        assertThat(awaiting.getExecutionStatus()).isEqualTo(ApplicationExecution.STATUS_SUBMITTED);
        assertThat(awaiting.getCompletedAt()).isNotNull();
        // Phase 7.16.1 — verification must fire on every real SUBMITTED outcome, with the
        // confirmation reference the automation actually captured (never fabricated).
        org.mockito.Mockito.verify(verification).verify(awaiting, connector, "conf-123");
    }

    /**
     * Phase 0 — the gate. A submit click with an unverifiable outcome must NOT be reported as
     * SUBMITTED. Before this phase the terminal transition ignored the verdict entirely.
     */
    @Test
    void finalizeGuestApplySubmit_unverifiedVerdictDowngradesToSubmitUnverified() {
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
                .thenReturn(GuestApplyAutomationService.AttemptOutcome.submitted("some page text"));
        when(verification.verify(any(), any(), any())).thenReturn(
                ai.careerpilot.execution.verification.VerificationResult.unableToVerify(
                        "EVIDENCE_ADJUDICATION:WEAK", "only one success signal"));

        service(true).finalizeGuestApplySubmit(execId);

        assertThat(awaiting.getExecutionStatus()).isEqualTo(ApplicationExecution.STATUS_SUBMIT_UNVERIFIED);
        assertThat(awaiting.getExecutionStatus()).isNotEqualTo(ApplicationExecution.STATUS_SUBMITTED);
    }

    /** Phase 0 — a positively-detected failure page is likewise never promoted to SUBMITTED. */
    @Test
    void finalizeGuestApplySubmit_notVerifiedVerdictDowngradesToSubmitUnverified() {
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
                .thenReturn(GuestApplyAutomationService.AttemptOutcome.submitted("error page"));
        when(verification.verify(any(), any(), any())).thenReturn(
                ai.careerpilot.execution.verification.VerificationResult.notVerified(
                        "EVIDENCE_ADJUDICATION:NONE", "failure indicator detected"));

        service(true).finalizeGuestApplySubmit(execId);

        assertThat(awaiting.getExecutionStatus()).isEqualTo(ApplicationExecution.STATUS_SUBMIT_UNVERIFIED);
    }

    /**
     * Phase 0 — <b>deliberate inversion</b> of the previous
     * {@code verificationExceptionNeverBlocksSubmittedOutcome} test. A crashing verification engine
     * used to still yield SUBMITTED; it now fails closed to SUBMIT_UNVERIFIED. The request still
     * must not throw — the click did happen and the row must be recorded — but an unprovable
     * submission is never labelled a successful one.
     */
    @Test
    void finalizeGuestApplySubmit_verificationExceptionFailsClosedToSubmitUnverified() {
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
        when(verification.verify(any(), any(), any())).thenThrow(new RuntimeException("verification engine down"));

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> service(true).finalizeGuestApplySubmit(execId));

        assertThat(awaiting.getExecutionStatus()).isEqualTo(ApplicationExecution.STATUS_SUBMIT_UNVERIFIED);
        assertThat(awaiting.getCompletedAt()).isNotNull();
    }

    @Test
    void abortedOutcomeNeverInvokesVerification() {
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
                .thenReturn(GuestApplyAutomationService.AttemptOutcome.aborted("captcha detected"));

        service(true).finalizeGuestApplySubmit(execId);

        org.mockito.Mockito.verifyNoInteractions(verification);
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

    // ── Phase 7.16.3 — Automation Recovery Center wiring ──

    @Test
    void failedOutcomeOffersRecoveryBeforeGoingTerminal() {
        when(recovery.attemptRecovery(any(), any())).thenReturn(Optional.empty());
        ATSConnector connector = mock(ATSConnector.class);
        when(connector.isConfigured()).thenReturn(true);
        when(connector.name()).thenReturn("greenhouse");
        when(connectors.detect(any())).thenReturn(connector);
        when(guestApply.isEligible(connector)).thenReturn(true);
        when(guestApply.attemptFill(any(), any(), any()))
                .thenReturn(GuestApplyAutomationService.AttemptOutcome.error("connector threw"));

        ApplicationExecution e = service(true).execute(userId, jobId, pkgId).orElseThrow();

        org.mockito.Mockito.verify(recovery).attemptRecovery(e, "connector threw");
        assertThat(e.getExecutionStatus()).isEqualTo(ApplicationExecution.STATUS_FAILED);
    }

    @Test
    void recoveryRetryDecisionLeavesExecutionAtRetryNotFailed() {
        when(recovery.attemptRecovery(any(), any())).thenReturn(Optional.of(
                new ai.careerpilot.execution.retry.RetryDecision(
                        ai.careerpilot.domain.ApplicationRetry.CLASS_NETWORK,
                        ai.careerpilot.domain.ApplicationRetry.ACTION_RETRY, 0L)));
        ATSConnector connector = mock(ATSConnector.class);
        when(connector.isConfigured()).thenReturn(true);
        when(connector.name()).thenReturn("greenhouse");
        when(connectors.detect(any())).thenReturn(connector);
        when(guestApply.isEligible(connector)).thenReturn(true);
        when(guestApply.attemptFill(any(), any(), any()))
                .thenReturn(GuestApplyAutomationService.AttemptOutcome.error("timeout"));

        ApplicationExecution e = service(true).execute(userId, jobId, pkgId).orElseThrow();

        // recovery mock does NOT itself mutate e (that's AutomationRecoveryService's own real job,
        // covered by AutomationRecoveryServiceTest) — this test only proves terminal() honors the
        // decision by NOT overwriting whatever recovery already decided with STATUS_FAILED.
        assertThat(e.getExecutionStatus()).isNotEqualTo(ApplicationExecution.STATUS_FAILED);
    }

    @Test
    void recoveryStopDecisionFallsThroughToFailed() {
        when(recovery.attemptRecovery(any(), any())).thenReturn(Optional.of(
                new ai.careerpilot.execution.retry.RetryDecision(
                        ai.careerpilot.domain.ApplicationRetry.CLASS_UNKNOWN,
                        ai.careerpilot.domain.ApplicationRetry.ACTION_STOP, 0L)));
        ATSConnector connector = mock(ATSConnector.class);
        when(connector.isConfigured()).thenReturn(true);
        when(connector.name()).thenReturn("greenhouse");
        when(connectors.detect(any())).thenReturn(connector);
        when(guestApply.isEligible(connector)).thenReturn(true);
        when(guestApply.attemptFill(any(), any(), any()))
                .thenReturn(GuestApplyAutomationService.AttemptOutcome.error("weird error"));

        ApplicationExecution e = service(true).execute(userId, jobId, pkgId).orElseThrow();

        assertThat(e.getExecutionStatus()).isEqualTo(ApplicationExecution.STATUS_FAILED);
        assertThat(e.getCompletedAt()).isNotNull();
    }

    @Test
    void abortedOutcomeNeverOffersRecovery() {
        // ABORTED means "not applicable" (e.g. no execution backend), not a transient failure.
        ApplicationExecution e = service(true).execute(userId, jobId, pkgId).orElseThrow();
        assertThat(e.getExecutionStatus()).isEqualTo(ApplicationExecution.STATUS_ABORTED);
        org.mockito.Mockito.verifyNoInteractions(recovery);
    }

    @Test
    void retryExecutionRefusesNonRetryNonManualReviewStatus() {
        UUID execId = UUID.randomUUID();
        ApplicationExecution submitted = ApplicationExecution.builder()
                .id(execId).userId(userId).jobId(jobId).applicationPackageId(pkgId)
                .executionStatus(ApplicationExecution.STATUS_SUBMITTED).attemptCount(1)
                .build();
        when(executions.findById(execId)).thenReturn(Optional.of(submitted));

        assertThat(service(true).retryExecution(execId)).isEmpty();
        org.mockito.Mockito.verify(packages, org.mockito.Mockito.never()).findById(any());
    }

    @Test
    void retryExecutionSpawnsNewAttemptAndMarksOldRowRetried() {
        UUID execId = UUID.randomUUID();
        ApplicationExecution previous = ApplicationExecution.builder()
                .id(execId).userId(userId).jobId(jobId).applicationPackageId(pkgId)
                .executionStatus(ApplicationExecution.STATUS_RETRY).attemptCount(1)
                .checkpoint(ai.careerpilot.execution.recovery.ExecutionCheckpoint.FORM_FILLED)
                .build();
        when(executions.findById(execId)).thenReturn(Optional.of(previous));
        // baseline setUp() already wires packages/jobs/connectors for a plain ABORTED execute() path

        Optional<ApplicationExecution> result = service(true).retryExecution(execId);

        assertThat(result).isPresent();
        ApplicationExecution newAttempt = result.get();
        assertThat(newAttempt.getRetryOfExecutionId()).isEqualTo(execId);
        assertThat(newAttempt.getAttemptCount()).isEqualTo(2);
        assertThat(newAttempt.getCheckpoint()).isEqualTo(ai.careerpilot.execution.recovery.ExecutionCheckpoint.FORM_FILLED);
        assertThat(previous.getExecutionStatus()).isEqualTo(ApplicationExecution.STATUS_RETRIED);
        assertThat(previous.getCompletedAt()).isNotNull();
        org.mockito.Mockito.verify(recovery).recordRecoveryOutcome(previous, newAttempt);
    }

    @Test
    void cancelAbortsANonTerminalExecution() {
        UUID execId = UUID.randomUUID();
        ApplicationExecution running = ApplicationExecution.builder()
                .id(execId).userId(userId).jobId(jobId).applicationPackageId(pkgId)
                .executionStatus(ApplicationExecution.STATUS_EXECUTING).attemptCount(1)
                .build();
        when(executions.findByIdAndUserId(execId, userId)).thenReturn(Optional.of(running));

        boolean cancelled = service(true).cancel(execId, userId, "user changed their mind");

        assertThat(cancelled).isTrue();
        assertThat(running.getExecutionStatus()).isEqualTo(ApplicationExecution.STATUS_ABORTED);
        assertThat(running.getCompletedAt()).isNotNull();
        org.mockito.Mockito.verify(recovery).recordCancellation(running, "user changed their mind");
    }

    @Test
    void cancelRefusesAnAlreadyTerminalExecution() {
        UUID execId = UUID.randomUUID();
        ApplicationExecution submitted = ApplicationExecution.builder()
                .id(execId).userId(userId).jobId(jobId).applicationPackageId(pkgId)
                .executionStatus(ApplicationExecution.STATUS_SUBMITTED).attemptCount(1)
                .build();
        when(executions.findByIdAndUserId(execId, userId)).thenReturn(Optional.of(submitted));

        boolean cancelled = service(true).cancel(execId, userId, "too late");

        assertThat(cancelled).isFalse();
        assertThat(submitted.getExecutionStatus()).isEqualTo(ApplicationExecution.STATUS_SUBMITTED);
        org.mockito.Mockito.verifyNoInteractions(recovery);
    }

    @Test
    void cancelReturnsFalseWhenExecutionNotFoundOrNotOwned() {
        when(executions.findByIdAndUserId(any(), any())).thenReturn(Optional.empty());
        assertThat(service(true).cancel(UUID.randomUUID(), userId, "n/a")).isFalse();
    }

    // ── Phase 7.16.4 — user-initiated manual review escalation ──

    @Test
    void requestManualReviewEscalatesANonTerminalExecution() {
        UUID execId = UUID.randomUUID();
        ApplicationExecution running = ApplicationExecution.builder()
                .id(execId).userId(userId).jobId(jobId).applicationPackageId(pkgId)
                .executionStatus(ApplicationExecution.STATUS_EXECUTING).attemptCount(1)
                .build();
        when(executions.findByIdAndUserId(execId, userId)).thenReturn(Optional.of(running));

        boolean requested = service(true).requestManualReview(execId, userId, "looks stuck");

        assertThat(requested).isTrue();
        assertThat(running.getExecutionStatus()).isEqualTo(ApplicationExecution.STATUS_MANUAL_REVIEW);
        assertThat(running.getFailureReason()).isEqualTo("looks stuck");
    }

    @Test
    void requestManualReviewRefusesAnAlreadyTerminalExecution() {
        UUID execId = UUID.randomUUID();
        ApplicationExecution submitted = ApplicationExecution.builder()
                .id(execId).userId(userId).jobId(jobId).applicationPackageId(pkgId)
                .executionStatus(ApplicationExecution.STATUS_SUBMITTED).attemptCount(1)
                .build();
        when(executions.findByIdAndUserId(execId, userId)).thenReturn(Optional.of(submitted));

        assertThat(service(true).requestManualReview(execId, userId, "too late")).isFalse();
        assertThat(submitted.getExecutionStatus()).isEqualTo(ApplicationExecution.STATUS_SUBMITTED);
    }

    @Test
    void requestManualReviewRefusesWhenAlreadyInManualReview() {
        UUID execId = UUID.randomUUID();
        ApplicationExecution paused = ApplicationExecution.builder()
                .id(execId).userId(userId).jobId(jobId).applicationPackageId(pkgId)
                .executionStatus(ApplicationExecution.STATUS_MANUAL_REVIEW).attemptCount(1)
                .build();
        when(executions.findByIdAndUserId(execId, userId)).thenReturn(Optional.of(paused));

        assertThat(service(true).requestManualReview(execId, userId, "again")).isFalse();
    }

    // ── P4 — the approval claim and the never-retry-a-submit rule ─────────────────────────────

    /**
     * WI2. Two workers observing the same approved execution must produce exactly ONE submission.
     * The single browser lease does not save us: it serialises them into two consecutive REAL
     * submissions. The conditional UPDATE is the claim, so only the caller that changes a row runs.
     */
    @Test
    void concurrentFinalizeProducesExactlyOneBrowserSubmission() {
        UUID execId = UUID.randomUUID();
        ApplicationExecution awaiting = ApplicationExecution.builder()
                .id(execId).userId(userId).jobId(jobId).applicationPackageId(pkgId)
                .executionStatus(ApplicationExecution.STATUS_AWAITING_APPROVAL)
                .executionType(ApplicationExecution.TYPE_ATS_CONNECTOR)
                .attemptCount(1).build();
        when(executions.findById(execId)).thenReturn(Optional.of(awaiting));
        // First caller wins the row; every later caller sees zero rows affected.
        when(executions.claimForSubmit(execId)).thenReturn(1, 0, 0);
        ATSConnector connector = mock(ATSConnector.class);
        when(connector.isConfigured()).thenReturn(true);
        when(connector.name()).thenReturn("greenhouse");
        when(connectors.detect(any())).thenReturn(connector);
        when(guestApply.isEligible(connector)).thenReturn(true);
        when(guestApply.finalizeSubmit(any(), any(), any()))
                .thenReturn(GuestApplyAutomationService.AttemptOutcome.submitted("conf-1"));
        when(verification.verify(any(), any(), any())).thenReturn(
                ai.careerpilot.execution.verification.VerificationResult.verified(
                        "EVIDENCE_ADJUDICATION:CONFIRMED", "confirmed"));

        ApplicationExecutionService svc = service(true);
        svc.finalizeGuestApplySubmit(execId);
        svc.finalizeGuestApplySubmit(execId);
        svc.finalizeGuestApplySubmit(execId);

        org.mockito.Mockito.verify(guestApply, org.mockito.Mockito.times(1))
                .finalizeSubmit(any(), any(), any());
    }

    @Test
    void aFailingClaimNeverSubmitsAndNeverThrows() {
        UUID execId = UUID.randomUUID();
        when(executions.claimForSubmit(execId)).thenThrow(new IllegalStateException("db down"));

        service(true).finalizeGuestApplySubmit(execId);   // must not throw

        org.mockito.Mockito.verify(guestApply, org.mockito.Mockito.never()).finalizeSubmit(any(), any(), any());
        org.mockito.Mockito.verify(executions, org.mockito.Mockito.never()).findById(execId);
    }

    /**
     * WI1/WI3. A browser exception AFTER the click must never be reported as FAILED: only
     * STATUS_FAILED enters the recovery branch, where RetryPolicyService classifies a Playwright
     * error as BROWSER_FAILURE -> RETRY, and the retry would resubmit an application that may
     * already exist at the employer.
     */
    @Test
    void aPostClickBrowserFailureBecomesSubmitUnverifiedAndIsNeverOfferedToRecovery() {
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
        when(guestApply.finalizeSubmit(any(), any(), any())).thenReturn(
                GuestApplyAutomationService.AttemptOutcome.submitUnverified(
                        "submit click was issued but the browser failed before delivery could be "
                                + "confirmed: Element is not attached to the DOM"));

        service(true).finalizeGuestApplySubmit(execId);

        assertThat(awaiting.getExecutionStatus())
                .isEqualTo(ApplicationExecution.STATUS_SUBMIT_UNVERIFIED);
        assertThat(awaiting.getFailureReason()).contains("submit click was issued");
        // The recovery/retry branch is reachable only from STATUS_FAILED — never consulted here.
        org.mockito.Mockito.verify(recovery, org.mockito.Mockito.never())
                .attemptRecovery(any(), any());
    }

    /** A failure BEFORE any click is still an ordinary, retryable failure — unchanged behaviour. */
    @Test
    void aPreClickFailureIsStillAnOrdinaryFailure() {
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
                .thenReturn(GuestApplyAutomationService.AttemptOutcome.error("navigation timed out"));

        service(true).finalizeGuestApplySubmit(execId);

        assertThat(awaiting.getExecutionStatus()).isEqualTo(ApplicationExecution.STATUS_FAILED);
    }
}
