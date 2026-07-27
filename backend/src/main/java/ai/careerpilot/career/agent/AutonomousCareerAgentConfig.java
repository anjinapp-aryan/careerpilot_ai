package ai.careerpilot.career.agent;

import ai.careerpilot.career.monitor.CareerMonitor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Phase 11.6 — the only place any autonomous-agent bean is constructed, gated by the single
 * {@code career.agent.enabled} flag (default {@code false}, matching every prior Phase 11
 * sub-phase's single-flag simplicity). {@link CareerMonitor} (Phase 11.5, own flag {@code
 * career.monitor.enabled}) is injected via {@link ObjectProvider} — independent flags, so {@link
 * DefaultAutonomousCareerAgent} degrades to an empty observation if it's off even while this one
 * is on. No {@code @Scheduled} bean exists here — nothing triggers {@link
 * AutonomousCareerAgent#runOnce} automatically; see the package javadoc.
 */
@Configuration
public class AutonomousCareerAgentConfig {

    @Bean
    @ConditionalOnProperty(prefix = "career.agent", name = "enabled", havingValue = "true")
    public AgentMetrics agentMetrics() {
        return new InMemoryAgentMetrics();
    }

    @Bean
    @ConditionalOnProperty(prefix = "career.agent", name = "enabled", havingValue = "true")
    public AgentMemory agentMemory() {
        return new InMemoryAgentMemory();
    }

    @Bean
    @ConditionalOnProperty(prefix = "career.agent", name = "enabled", havingValue = "true")
    public TaskScheduler taskScheduler() {
        return new DefaultTaskScheduler();
    }

    @Bean
    @ConditionalOnProperty(prefix = "career.agent", name = "enabled", havingValue = "true")
    public AgentTaskExecutor agentTaskExecutor() {
        return new DeferredAgentTaskExecutor();
    }

    @Bean
    @ConditionalOnProperty(prefix = "career.agent", name = "enabled", havingValue = "true")
    public AgentPlanner agentPlanner(@Value("${career.agent.max-tasks-per-plan:5}") int maxTasksPerPlan) {
        return new DefaultAgentPlanner(maxTasksPerPlan);
    }

    @Bean
    @ConditionalOnProperty(prefix = "career.agent", name = "enabled", havingValue = "true")
    public AutonomousCareerAgent autonomousCareerAgent(
            ObjectProvider<CareerMonitor> careerMonitorProvider, AgentPlanner planner, AgentTaskExecutor executor,
            TaskScheduler scheduler, AgentMemory memory, AgentMetrics metrics,
            @Value("${career.agent.min-run-interval-hours:24}") long minRunIntervalHours) {
        return new DefaultAutonomousCareerAgent(careerMonitorProvider, planner, executor, scheduler, memory, metrics,
                Duration.ofHours(minRunIntervalHours));
    }
}
