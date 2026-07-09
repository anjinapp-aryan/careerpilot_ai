package ai.careerpilot.workflow.api;

import ai.careerpilot.repo.WorkflowCorrelationRepository;
import ai.careerpilot.repo.WorkflowDeadLetterRepository;
import ai.careerpilot.workflow.analytics.ApplicationAnalyticsMetrics;
import ai.careerpilot.workflow.career.CareerIntelligenceMetrics;
import ai.careerpilot.workflow.correlation.WorkflowMetrics;
import ai.careerpilot.workflow.email.EmailIntelligenceMetrics;
import ai.careerpilot.workflow.interview.InterviewMetrics;
import ai.careerpilot.workflow.timeline.TimelineMetrics;
import ai.careerpilot.workflow.tracking.WorkflowTrackingMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 3A — the 8 diagnostics endpoints are no-auth counts-only. At stock (dark) defaults every engine
 * stage must read {@code enabled:false} / {@code health:"NOT_CONFIGURED"}, proving the whole workflow
 * registers without activating. The two infra endpoints report UP when nothing is failing.
 */
class WorkflowDiagnosticsControllerTest {

    private ThreadPoolTaskExecutor exec() {
        ThreadPoolTaskExecutor e = new ThreadPoolTaskExecutor();
        e.setCorePoolSize(1);
        e.setMaxPoolSize(1);
        e.setQueueCapacity(10);
        e.initialize();
        return e;
    }

    private final WorkflowCorrelationRepository correlationRepo = mock(WorkflowCorrelationRepository.class);
    private final WorkflowDeadLetterRepository deadLetterRepo = mock(WorkflowDeadLetterRepository.class);

    private WorkflowDiagnosticsController controller() {
        return new WorkflowDiagnosticsController(
                new WorkflowTrackingMetrics(), new TimelineMetrics(), new EmailIntelligenceMetrics(),
                new InterviewMetrics(), new ApplicationAnalyticsMetrics(), new CareerIntelligenceMetrics(),
                new WorkflowMetrics(), correlationRepo, deadLetterRepo,
                exec(), exec(), exec(), exec(), exec(), exec());
    }

    @Test
    void everyEngineStageIsNotConfiguredAtStockDefaults() {
        WorkflowDiagnosticsController c = controller();
        for (Map<String, Object> stage : new Map[]{
                c.applicationTracking(), c.applicationTimeline(), c.emailIntelligence(),
                c.interviewTracking(), c.applicationAnalytics(), c.careerIntelligence()}) {
            assertThat(stage.get("enabled")).isEqualTo(false);
            assertThat(stage.get("triggerEnabled")).isEqualTo(false);
            assertThat(stage.get("health")).isEqualTo("NOT_CONFIGURED");
        }
    }

    @Test
    void engineStagesExposeExecutorAndCounters() {
        Map<String, Object> tracking = controller().applicationTracking();
        assertThat(tracking).containsKeys("trackingTotal", "trackingFailures",
                "executorQueueSize", "executorQueueCapacity", "health");
    }

    @Test
    void correlationInfraIsUpWhenNoFailures() {
        when(correlationRepo.countByStatus(org.mockito.ArgumentMatchers.anyString())).thenReturn(0L);
        Map<String, Object> m = controller().workflowCorrelation();
        assertThat(m.get("health")).isEqualTo("UP");
        assertThat(m).containsKey("correlationsStarted");
    }

    @Test
    void deadLetterInfraIsUpWhenNoRecordFailures() {
        when(deadLetterRepo.count()).thenReturn(3L);
        Map<String, Object> m = controller().workflowDeadLetter();
        assertThat(m.get("health")).isEqualTo("UP"); // captured dead letters are the system working, not unhealthy
        assertThat(m.get("deadLetterRowsTotal")).isEqualTo(3L);
    }
}
