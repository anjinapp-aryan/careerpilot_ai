package ai.careerpilot.career.agent;

import ai.careerpilot.career.monitor.CareerAlert;
import ai.careerpilot.career.monitor.CareerAlertSeverity;
import ai.careerpilot.career.monitor.CareerAlertType;
import ai.careerpilot.career.monitor.CareerInsights;
import ai.careerpilot.career.monitor.CareerMonitor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultAutonomousCareerAgentTest {

    private final AgentMemory memory = new InMemoryAgentMemory();
    private final InMemoryAgentMetrics metrics = new InMemoryAgentMetrics();
    private final DefaultTaskScheduler scheduler = new DefaultTaskScheduler();

    @SuppressWarnings("unchecked")
    private <T> ObjectProvider<T> providerFor(T value) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(value);
        return p;
    }

    private CareerAlert alert(UUID userId, CareerAlertType type) {
        return CareerAlert.of(userId, type, CareerAlertSeverity.HIGH, "msg", Map.of());
    }

    @Test
    void skipsRunWhenNotEligible() {
        UUID userId = UUID.randomUUID();
        scheduler.recordRun(userId);
        AgentPlanner planner = obs -> { throw new AssertionError("should not be called"); };
        AgentTaskExecutor executor = (type, uid) -> { throw new AssertionError("should not be called"); };
        DefaultAutonomousCareerAgent agent = new DefaultAutonomousCareerAgent(
                providerFor(null), planner, executor, scheduler, memory, metrics, Duration.ofHours(24));

        AgentReflection reflection = agent.runOnce(userId);

        assertThat(reflection.plan().isEmpty()).isTrue();
        assertThat(reflection.assessment()).contains("skipped");
        assertThat(metrics.skippedRunCount("cooldown")).isEqualTo(1);
    }

    @Test
    void noCareerMonitorAvailable_observesEmptyAndStillCompletesRun() {
        UUID userId = UUID.randomUUID();
        AgentPlanner planner = new DefaultAgentPlanner(5);
        AgentTaskExecutor executor = new DeferredAgentTaskExecutor();
        DefaultAutonomousCareerAgent agent = new DefaultAutonomousCareerAgent(
                providerFor(null), planner, executor, scheduler, memory, metrics, Duration.ofHours(24));

        AgentReflection reflection = agent.runOnce(userId);

        assertThat(reflection.plan().isEmpty()).isTrue();
        assertThat(memory.recentFor(userId, 5)).hasSize(1);
        assertThat(metrics.runCount()).isEqualTo(1);
    }

    @Test
    void careerMonitorProvidesSignals_plannedTasksAreDispatchedAndDeferred() {
        UUID userId = UUID.randomUUID();
        CareerMonitor monitor = mock(CareerMonitor.class);
        CareerInsights insights = new CareerInsights(userId, List.of(), List.of(alert(userId, CareerAlertType.JOB_MATCH)),
                java.time.Instant.now(), "1 insight");
        when(monitor.monitor(userId)).thenReturn(insights);

        AgentPlanner planner = new DefaultAgentPlanner(5);
        AgentTaskExecutor executor = new DeferredAgentTaskExecutor();
        DefaultAutonomousCareerAgent agent = new DefaultAutonomousCareerAgent(
                providerFor(monitor), planner, executor, scheduler, memory, metrics, Duration.ofHours(24));

        AgentReflection reflection = agent.runOnce(userId);

        assertThat(reflection.plan().tasks()).containsExactly(AgentTaskType.JOB_DISCOVERY);
        assertThat(reflection.results()).hasSize(1);
        assertThat(reflection.results().get(0).outcome()).isEqualTo(TaskOutcome.DEFERRED);
        assertThat(metrics.taskOutcomeCount("JOB_DISCOVERY", "DEFERRED")).isEqualTo(1);
    }

    @Test
    void careerMonitorThrows_fallsBackToEmptyObservationRatherThanFailingRun() {
        UUID userId = UUID.randomUUID();
        CareerMonitor monitor = mock(CareerMonitor.class);
        when(monitor.monitor(any())).thenThrow(new RuntimeException("boom"));

        AgentPlanner planner = new DefaultAgentPlanner(5);
        AgentTaskExecutor executor = new DeferredAgentTaskExecutor();
        DefaultAutonomousCareerAgent agent = new DefaultAutonomousCareerAgent(
                providerFor(monitor), planner, executor, scheduler, memory, metrics, Duration.ofHours(24));

        AgentReflection reflection = agent.runOnce(userId);

        assertThat(reflection.plan().isEmpty()).isTrue();
    }

    @Test
    void successfulRunRecordsSchedulerAndMemory() {
        UUID userId = UUID.randomUUID();
        AgentPlanner planner = new DefaultAgentPlanner(5);
        AgentTaskExecutor executor = new DeferredAgentTaskExecutor();
        DefaultAutonomousCareerAgent agent = new DefaultAutonomousCareerAgent(
                providerFor(null), planner, executor, scheduler, memory, metrics, Duration.ofHours(24));

        agent.runOnce(userId);

        assertThat(scheduler.isEligibleToRun(userId, Duration.ofHours(24))).isFalse();
        assertThat(memory.recentFor(userId, 5)).hasSize(1);
    }
}
