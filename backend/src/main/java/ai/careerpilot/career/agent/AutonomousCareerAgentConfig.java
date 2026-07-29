package ai.careerpilot.career.agent;

import ai.careerpilot.career.monitor.CareerMonitor;
import ai.careerpilot.mission.MissionAwareDailyBriefService;
import ai.careerpilot.mission.WorkflowPlanner;
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
 *
 * <p><b>Phase 7A</b> — {@link MissionAwareDailyBriefService} (Phase 6A) and {@link WorkflowPlanner}
 * (Phase 6A.1's extension point, reused rather than duplicated) are injected the same way:
 * {@link ObjectProvider}, so an absent bean never breaks context startup. {@code
 * career.mission.agent.enabled} (default {@code false}, its own flag, independent of {@code
 * career.agent.enabled}) is the master switch for whether {@link DefaultAutonomousCareerAgent}
 * ever consults them at all.
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
            ObjectProvider<MissionAwareDailyBriefService> missionBriefProvider,
            ObjectProvider<WorkflowPlanner> workflowPlannerProvider,
            @Value("${career.agent.min-run-interval-hours:24}") long minRunIntervalHours,
            @Value("${career.mission.agent.enabled:false}") boolean missionAwareEnabled) {
        return new DefaultAutonomousCareerAgent(careerMonitorProvider, planner, executor, scheduler, memory, metrics,
                Duration.ofHours(minRunIntervalHours), missionBriefProvider, workflowPlannerProvider, missionAwareEnabled);
    }
}
