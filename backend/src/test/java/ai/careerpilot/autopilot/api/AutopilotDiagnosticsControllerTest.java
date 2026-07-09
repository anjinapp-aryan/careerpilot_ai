package ai.careerpilot.autopilot.api;

import ai.careerpilot.autopilot.orchestrator.AutopilotMetrics;
import ai.careerpilot.autopilot.provider.ApplicationProviderRegistry;
import ai.careerpilot.autopilot.provider.CompanyPortalProvider;
import ai.careerpilot.autopilot.provider.GreenhouseProvider;
import ai.careerpilot.repo.ApplicationDecisionRepository;
import ai.careerpilot.repo.ApplicationSubmissionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Stock (dark) defaults must report NOT_CONFIGURED everywhere — never fabricate an UP status. */
class AutopilotDiagnosticsControllerTest {

    private AutopilotDiagnosticsController controller;
    private ApplicationDecisionRepository decisions;
    private ApplicationSubmissionRepository submissions;

    @BeforeEach
    void setUp() {
        decisions = mock(ApplicationDecisionRepository.class);
        submissions = mock(ApplicationSubmissionRepository.class);
        when(decisions.count()).thenReturn(0L);
        when(decisions.countByOutcome(any())).thenReturn(0L);
        when(submissions.count()).thenReturn(0L);
        when(submissions.countByStatus(any())).thenReturn(0L);

        ApplicationProviderRegistry registry =
                new ApplicationProviderRegistry(List.of(new GreenhouseProvider(), new CompanyPortalProvider()));
        controller = new AutopilotDiagnosticsController(new AutopilotMetrics(), decisions, submissions,
                registry, realExecutor());
    }

    private static ThreadPoolTaskExecutor realExecutor() {
        ThreadPoolTaskExecutor e = new ThreadPoolTaskExecutor();
        e.setCorePoolSize(1);
        e.setMaxPoolSize(1);
        e.setQueueCapacity(10);
        e.initialize();
        return e;
    }

    @Test
    void overviewReportsNotConfiguredByDefault() {
        Map<String, Object> out = controller.overview();
        assertEquals(false, out.get("enabled"));
        assertEquals("NOT_CONFIGURED", out.get("health"));
        assertEquals(false, out.get("decisionEnabled"));
        assertEquals(false, out.get("autoApplyEnabled"));
        assertTrue(((List<?>) out.get("providers")).contains("greenhouse"));
    }

    @Test
    void decisionStageReportsCountsAndNotConfigured() {
        Map<String, Object> out = controller.decision();
        assertEquals("NOT_CONFIGURED", out.get("health"));
        assertEquals(0L, out.get("totalDecisions"));
        assertEquals(0L, out.get("outcome.AUTO_APPLY"));
    }

    @Test
    void applyStageReportsSubmissionCounts() {
        Map<String, Object> out = controller.apply();
        assertEquals("NOT_CONFIGURED", out.get("health"));
        assertEquals(0L, out.get("totalSubmissions"));
        assertEquals(0L, out.get("status.HUMAN_REVIEW"));
    }

    @Test
    void enabledOrchestratorReportsUpWhenQueueHealthy() {
        ReflectionTestUtils.setField(controller, "orchestratorEnabled", true);
        assertEquals("UP", controller.overview().get("health"));
    }
}
