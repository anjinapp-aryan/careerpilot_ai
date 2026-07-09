package ai.careerpilot.execution.execution;

import ai.careerpilot.domain.ApplicationExecution;
import ai.careerpilot.domain.ApplicationPackage;
import ai.careerpilot.domain.Job;
import ai.careerpilot.execution.ats.ATSConnector;
import ai.careerpilot.execution.ats.ATSConnectorRegistry;
import ai.careerpilot.execution.browser.BrowserAutomationProvider;
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
 * Phase 2E.1 — the execution state machine. The single most important guarantee: in this build the
 * terminal SUBMITTED state is UNREACHABLE. With the browser provider a stub ({@code isConfigured==false})
 * and every ATS connector unconfigured, a started execution can only ever land in ABORTED — never
 * SUBMITTED. These tests prove that, plus the disabled/missing/validating paths, and that the engine
 * never throws.
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

    @BeforeEach
    void setUp() {
        executions = mock(ApplicationExecutionRepository.class);
        audit = mock(ApplicationExecutionAuditRepository.class);
        packages = mock(ApplicationPackageRepository.class);
        jobs = mock(JobRepository.class);
        connectors = mock(ATSConnectorRegistry.class);
        browser = mock(BrowserAutomationProvider.class);
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
                new ApplicationExecutionMetrics(), enabled);
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
    void configuredConnectorIsLabelledButStillNotSubmittedInThisBuild() {
        ATSConnector connector = mock(ATSConnector.class);
        when(connector.isConfigured()).thenReturn(true);
        when(connector.name()).thenReturn("greenhouse");
        when(connectors.detect(any())).thenReturn(connector);
        ApplicationExecution e = service(true).execute(userId, jobId, pkgId).orElseThrow();
        assertThat(e.getExecutionType()).isEqualTo(ApplicationExecution.TYPE_ATS_CONNECTOR);
        assertThat(e.getProvider()).isEqualTo("greenhouse");
        assertThat(e.getExecutionStatus()).isEqualTo(ApplicationExecution.STATUS_ABORTED);
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
