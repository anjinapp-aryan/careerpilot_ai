package ai.careerpilot.execution.verification;

import ai.careerpilot.domain.ApplicationExecution;
import ai.careerpilot.execution.ats.ATSConnector;
import ai.careerpilot.repo.ApplicationExecutionAuditRepository;
import ai.careerpilot.repo.ApplicationExecutionRepository;
import ai.careerpilot.workflow.timeline.TimelineService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Phase 7.16.1 — the core trust-gap-closing contract: never fabricate VERIFIED, always persist
 * evidence onto the ApplicationExecution row, always append audit + timeline entries, and never
 * throw (a verification-engine crash must not be able to block the wider submission pipeline).
 */
class SubmissionVerificationServiceTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID jobId = UUID.randomUUID();
    private final UUID execId = UUID.randomUUID();

    private ApplicationExecution execution() {
        return ApplicationExecution.builder().id(execId).userId(userId).jobId(jobId)
                .executionStatus(ApplicationExecution.STATUS_EXECUTING).attemptCount(1).build();
    }

    private SubmissionVerificationService service(ApplicationExecutionRepository executions,
                                                   ApplicationExecutionAuditRepository audit,
                                                   TimelineService timeline) {
        return new SubmissionVerificationService(executions, audit, timeline, new VerificationMetrics());
    }

    @Test
    void verifiedConnectorResultPersistsEvidenceOntoExecution() {
        ApplicationExecutionRepository executions = mock(ApplicationExecutionRepository.class);
        ApplicationExecutionAuditRepository audit = mock(ApplicationExecutionAuditRepository.class);
        TimelineService timeline = mock(TimelineService.class);
        ATSConnector connector = mock(ATSConnector.class);
        when(connector.verifySubmission("some-confirmation")).thenReturn(
                VerificationResult.verified("POST_SUBMIT_PAGE_CAPTURE", "real evidence"));
        when(executions.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ApplicationExecution exec = execution();
        VerificationResult result = service(executions, audit, timeline).verify(exec, connector, "some-confirmation");

        assertEquals(VerificationStatus.VERIFIED, result.status());
        assertEquals("VERIFIED", exec.getVerificationStatus());
        assertEquals("some-confirmation", exec.getConfirmationNumber());
        assertEquals("POST_SUBMIT_PAGE_CAPTURE", exec.getVerificationMethod());
        assertNotNull(exec.getVerifiedAt());
    }

    @Test
    void nullConnectorNeverFabricatesVerified() {
        ApplicationExecutionRepository executions = mock(ApplicationExecutionRepository.class);
        ApplicationExecutionAuditRepository audit = mock(ApplicationExecutionAuditRepository.class);
        TimelineService timeline = mock(TimelineService.class);
        when(executions.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ApplicationExecution exec = execution();
        VerificationResult result = service(executions, audit, timeline).verify(exec, null, "anything");

        assertEquals(VerificationStatus.UNKNOWN, result.status());
        assertEquals("UNKNOWN", exec.getVerificationStatus());
        assertNotEquals("VERIFIED", exec.getVerificationStatus());
    }

    @Test
    void connectorExceptionBecomesUnknownNotAThrownException() {
        ApplicationExecutionRepository executions = mock(ApplicationExecutionRepository.class);
        ApplicationExecutionAuditRepository audit = mock(ApplicationExecutionAuditRepository.class);
        TimelineService timeline = mock(TimelineService.class);
        ATSConnector connector = mock(ATSConnector.class);
        when(connector.verifySubmission(any())).thenThrow(new RuntimeException("boom"));
        when(executions.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ApplicationExecution exec = execution();
        VerificationResult result = assertDoesNotThrow(() ->
                service(executions, audit, timeline).verify(exec, connector, "ref"));

        assertEquals(VerificationStatus.UNKNOWN, result.status());
    }

    @Test
    void everyVerificationAppendsAuditAndTimelineEntries() {
        ApplicationExecutionRepository executions = mock(ApplicationExecutionRepository.class);
        ApplicationExecutionAuditRepository audit = mock(ApplicationExecutionAuditRepository.class);
        TimelineService timeline = mock(TimelineService.class);
        ATSConnector connector = mock(ATSConnector.class);
        when(connector.verifySubmission(any())).thenReturn(VerificationResult.unableToVerify("NONE", "no evidence"));
        when(executions.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service(executions, audit, timeline).verify(execution(), connector, null);

        verify(audit, atLeast(3)).save(any()); // STARTED, EVIDENCE_STORED, VERIFICATION_<status>
        verify(timeline, atLeast(3)).append(eq(userId), eq(jobId), any(), eq("SUBMISSION_VERIFICATION"), any(), any());
    }

    @Test
    void auditWriteFailureNeverPropagates() {
        ApplicationExecutionRepository executions = mock(ApplicationExecutionRepository.class);
        ApplicationExecutionAuditRepository audit = mock(ApplicationExecutionAuditRepository.class);
        TimelineService timeline = mock(TimelineService.class);
        ATSConnector connector = mock(ATSConnector.class);
        when(connector.verifySubmission(any())).thenReturn(VerificationResult.verified("M", "R"));
        when(executions.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(audit.save(any())).thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() -> service(executions, audit, timeline).verify(execution(), connector, "ref"));
    }

    @Test
    void notVerifiedResultIsHonestlyRecordedNotUpgraded() {
        ApplicationExecutionRepository executions = mock(ApplicationExecutionRepository.class);
        ApplicationExecutionAuditRepository audit = mock(ApplicationExecutionAuditRepository.class);
        TimelineService timeline = mock(TimelineService.class);
        ATSConnector connector = mock(ATSConnector.class);
        when(connector.verifySubmission(any())).thenReturn(
                VerificationResult.notVerified("ATS_STATUS_CHECK", "ATS confirmed no application on file"));
        when(executions.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ArgumentCaptor<ApplicationExecution> captor = ArgumentCaptor.forClass(ApplicationExecution.class);
        service(executions, audit, timeline).verify(execution(), connector, "ref");
        verify(executions, atLeastOnce()).save(captor.capture());

        assertEquals("NOT_VERIFIED", captor.getValue().getVerificationStatus());
    }
}
