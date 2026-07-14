package ai.careerpilot.submission.api;

import ai.careerpilot.domain.ApplicationSubmissionSession;
import ai.careerpilot.repo.ApplicationSubmissionSessionRepository;
import ai.careerpilot.submission.ApplicationSubmissionSessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Stock (dark) defaults must report NOT_CONFIGURED — never fabricate an UP status. */
class ApplicationSubmissionDiagnosticsControllerTest {

    private ApplicationSubmissionSessionService service;
    private ApplicationSubmissionSessionRepository sessions;
    private ApplicationSubmissionDiagnosticsController controller;

    @BeforeEach
    void setUp() {
        service = mock(ApplicationSubmissionSessionService.class);
        sessions = mock(ApplicationSubmissionSessionRepository.class);
        controller = new ApplicationSubmissionDiagnosticsController(service, sessions, realExecutor(50));
    }

    private static ThreadPoolTaskExecutor realExecutor(int queueCapacity) {
        ThreadPoolTaskExecutor e = new ThreadPoolTaskExecutor();
        e.setCorePoolSize(1);
        e.setMaxPoolSize(1);
        e.setQueueCapacity(queueCapacity);
        e.initialize();
        return e;
    }

    private void zeroCounts() {
        when(sessions.countByStatus(anyString())).thenReturn(0L);
        when(sessions.count()).thenReturn(0L);
    }

    @Test
    void reportsNotConfiguredByDefault() {
        when(service.isEnabled()).thenReturn(false);
        zeroCounts();
        Map<String, Object> out = controller.diagnostics();
        assertEquals(false, out.get("enabled"));
        assertEquals("NOT_CONFIGURED", out.get("health"));
        assertEquals(0L, out.get("sessionsTotal"));
    }

    @Test
    void exposesAutoManualApprovalFlags() {
        when(service.isEnabled()).thenReturn(true);
        when(service.isAutoEnabled()).thenReturn(true);
        when(service.isManualEnabled()).thenReturn(true);
        when(service.isApprovalEnabled()).thenReturn(true);
        zeroCounts();
        Map<String, Object> out = controller.diagnostics();
        assertEquals(true, out.get("autoEnabled"));
        assertEquals(true, out.get("manualEnabled"));
        assertEquals(true, out.get("approvalEnabled"));
    }

    @Test
    void reportsUpWhenEnabledAndQueueHealthyAndNoFailures() {
        when(service.isEnabled()).thenReturn(true);
        when(sessions.count()).thenReturn(10L);
        when(sessions.countByStatus(ApplicationSubmissionSession.STATUS_COMPLETED)).thenReturn(10L);
        when(sessions.countByStatus(ApplicationSubmissionSession.STATUS_FAILED)).thenReturn(0L);
        when(sessions.countByStatus(ApplicationSubmissionSession.STATUS_WAITING_APPROVAL)).thenReturn(0L);
        when(sessions.countByStatus(ApplicationSubmissionSession.STATUS_SUBMITTING)).thenReturn(0L);
        assertEquals("UP", controller.diagnostics().get("health"));
    }

    @Test
    void reportsDegradedWhenFailureRateAboveThirtyPercent() {
        when(service.isEnabled()).thenReturn(true);
        when(sessions.count()).thenReturn(10L);
        when(sessions.countByStatus(ApplicationSubmissionSession.STATUS_COMPLETED)).thenReturn(6L);
        when(sessions.countByStatus(ApplicationSubmissionSession.STATUS_FAILED)).thenReturn(4L);
        when(sessions.countByStatus(ApplicationSubmissionSession.STATUS_WAITING_APPROVAL)).thenReturn(0L);
        when(sessions.countByStatus(ApplicationSubmissionSession.STATUS_SUBMITTING)).thenReturn(0L);
        assertEquals("DEGRADED", controller.diagnostics().get("health"));
    }

    @Test
    void reportsUpWhenFailureRateExactlyThirtyPercent() {
        when(service.isEnabled()).thenReturn(true);
        when(sessions.count()).thenReturn(10L);
        when(sessions.countByStatus(ApplicationSubmissionSession.STATUS_COMPLETED)).thenReturn(7L);
        when(sessions.countByStatus(ApplicationSubmissionSession.STATUS_FAILED)).thenReturn(3L);
        when(sessions.countByStatus(ApplicationSubmissionSession.STATUS_WAITING_APPROVAL)).thenReturn(0L);
        when(sessions.countByStatus(ApplicationSubmissionSession.STATUS_SUBMITTING)).thenReturn(0L);
        // 3*100 = 300, total*30 = 300 -> not strictly greater, so UP not DEGRADED.
        assertEquals("UP", controller.diagnostics().get("health"));
    }

    @Test
    void reportsDownWhenQueueSaturated() throws InterruptedException {
        when(service.isEnabled()).thenReturn(true);
        zeroCounts();
        ThreadPoolTaskExecutor saturated = new ThreadPoolTaskExecutor();
        saturated.setCorePoolSize(1);
        saturated.setMaxPoolSize(1);
        saturated.setQueueCapacity(1);
        saturated.initialize();
        // Fill the single worker thread + saturate the 1-slot queue.
        saturated.execute(() -> { try { Thread.sleep(300); } catch (InterruptedException ignored) {} });
        saturated.execute(() -> { });
        ApplicationSubmissionDiagnosticsController c = new ApplicationSubmissionDiagnosticsController(service, sessions, saturated);
        assertEquals("DOWN", c.diagnostics().get("health"));
    }

    @Test
    void exposesExecutorMetrics() {
        when(service.isEnabled()).thenReturn(false);
        zeroCounts();
        Map<String, Object> out = controller.diagnostics();
        assertTrue(out.containsKey("executorActiveCount"));
        assertTrue(out.containsKey("executorPoolSize"));
        assertTrue(out.containsKey("executorQueueSize"));
        assertEquals(50, out.get("executorQueueCapacity"));
    }

    @Test
    void exposesSessionCountsFromRepository() {
        when(service.isEnabled()).thenReturn(true);
        when(sessions.count()).thenReturn(5L);
        when(sessions.countByStatus(ApplicationSubmissionSession.STATUS_COMPLETED)).thenReturn(2L);
        when(sessions.countByStatus(ApplicationSubmissionSession.STATUS_FAILED)).thenReturn(1L);
        when(sessions.countByStatus(ApplicationSubmissionSession.STATUS_WAITING_APPROVAL)).thenReturn(1L);
        when(sessions.countByStatus(ApplicationSubmissionSession.STATUS_SUBMITTING)).thenReturn(1L);
        Map<String, Object> out = controller.diagnostics();
        assertEquals(5L, out.get("sessionsTotal"));
        assertEquals(2L, out.get("sessionsCompleted"));
        assertEquals(1L, out.get("sessionsFailed"));
        assertEquals(1L, out.get("sessionsWaitingApproval"));
        assertEquals(1L, out.get("sessionsSubmitting"));
    }
}
