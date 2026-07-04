package ai.careerpilot.api;

import ai.careerpilot.ai.AiGatewayService;
import ai.careerpilot.execution.api.ExecutionDiagnosticsController;
import ai.careerpilot.jobdiscovery.cache.MatchCacheMetrics;
import ai.careerpilot.retention.RetentionService;
import ai.careerpilot.workflow.api.WorkflowDiagnosticsController;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The aggregate observability endpoint composes the existing per-stage diagnostics beans and rolls their
 * {@code health} verdicts up worst-of. It must never disagree with the individual endpoints (it reuses
 * them) and, at stock dark defaults, roll up to {@code NOT_CONFIGURED}.
 */
class ObservabilityControllerTest {

    private final WorkflowDiagnosticsController workflow = mock(WorkflowDiagnosticsController.class);
    private final ExecutionDiagnosticsController execution = mock(ExecutionDiagnosticsController.class);
    private final AiGatewayService gateway = mock(AiGatewayService.class);
    private final MatchCacheMetrics matchCache = mock(MatchCacheMetrics.class);
    private final RetentionService retention = mock(RetentionService.class);

    private final ObservabilityController controller =
            new ObservabilityController(workflow, execution, gateway, matchCache, retention);

    private Map<String, Object> stage(String health) {
        return Map.of("enabled", false, "health", health);
    }

    private void stubAllNotConfigured() {
        when(workflow.applicationTracking()).thenReturn(stage("NOT_CONFIGURED"));
        when(workflow.applicationTimeline()).thenReturn(stage("NOT_CONFIGURED"));
        when(workflow.emailIntelligence()).thenReturn(stage("NOT_CONFIGURED"));
        when(workflow.interviewTracking()).thenReturn(stage("NOT_CONFIGURED"));
        when(workflow.applicationAnalytics()).thenReturn(stage("NOT_CONFIGURED"));
        when(workflow.careerIntelligence()).thenReturn(stage("NOT_CONFIGURED"));
        when(workflow.workflowCorrelation()).thenReturn(stage("UP"));
        when(workflow.workflowDeadLetter()).thenReturn(stage("UP"));
        when(execution.applicationExecution()).thenReturn(stage("NOT_CONFIGURED"));
        when(execution.browser()).thenReturn(stage("NOT_CONFIGURED"));
        when(execution.ats()).thenReturn(stage("NOT_CONFIGURED"));
        when(execution.tracking()).thenReturn(stage("NOT_CONFIGURED"));
        when(execution.analytics()).thenReturn(stage("NOT_CONFIGURED"));
        when(matchCache.snapshot()).thenReturn(Map.of("hits", 0L, "misses", 0L));
        when(retention.isEnabled()).thenReturn(false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aggregatesAllSectionsAndRollsUpWorstOf() {
        stubAllNotConfigured();
        when(gateway.health()).thenReturn(Map.of()); // no providers configured

        Map<String, Object> out = controller.observability();

        assertThat(out).containsKeys("workflow", "execution", "providers", "cache", "retention", "overall");
        // provider chain with no configured providers → NOT_CONFIGURED; cache/retention UP → overall UP
        assertThat(((Map<String, Object>) out.get("providers")).get("health")).isEqualTo("NOT_CONFIGURED");
        assertThat(out.get("overall")).isEqualTo("UP"); // cache + retention report UP
    }

    @Test
    @SuppressWarnings("unchecked")
    void aDownStageDominatesTheOverallVerdict() {
        stubAllNotConfigured();
        when(execution.applicationExecution()).thenReturn(stage("DOWN")); // saturated queue
        when(gateway.health()).thenReturn(Map.of("gemini", "UP"));

        Map<String, Object> out = controller.observability();

        assertThat(((Map<String, Object>) out.get("execution")).get("health")).isEqualTo("DOWN");
        assertThat(out.get("overall")).isEqualTo("DOWN");
    }

    @Test
    @SuppressWarnings("unchecked")
    void providerHealthUpWhenAnyProviderUp() {
        stubAllNotConfigured();
        when(gateway.health()).thenReturn(Map.of("deepseek", "DOWN", "gemini", "UP"));
        Map<String, Object> out = controller.observability();
        assertThat(((Map<String, Object>) out.get("providers")).get("health")).isEqualTo("UP");
    }

    @Test
    @SuppressWarnings("unchecked")
    void providerHealthDownWhenAllProvidersDown() {
        stubAllNotConfigured();
        when(gateway.health()).thenReturn(Map.of("deepseek", "DOWN", "gemini", "DOWN"));
        Map<String, Object> out = controller.observability();
        assertThat(((Map<String, Object>) out.get("providers")).get("health")).isEqualTo("DOWN");
    }
}
