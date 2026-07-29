package ai.careerpilot.career.agent;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AutonomousCareerAgentConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AutonomousCareerAgentConfig.class);

    @Test
    void withFlagAtDefault_noAgentBeansAreConstructed() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(AutonomousCareerAgent.class);
            assertThat(context).doesNotHaveBean(AgentPlanner.class);
            assertThat(context).doesNotHaveBean(AgentTaskExecutor.class);
            assertThat(context).doesNotHaveBean(TaskScheduler.class);
            assertThat(context).doesNotHaveBean(AgentMemory.class);
            assertThat(context).doesNotHaveBean(AgentMetrics.class);
        });
    }

    @Test
    void withFlagOn_allBeansConstructed() {
        contextRunner.withPropertyValues("career.agent.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(AutonomousCareerAgent.class);
            assertThat(context).hasSingleBean(AgentPlanner.class);
            assertThat(context.getBean(AgentTaskExecutor.class)).isInstanceOf(DeferredAgentTaskExecutor.class);
        });
    }

    @Test
    void endToEnd_wiredAgentRunsWithoutErrorAndNoCareerMonitorAvailable() {
        contextRunner.withPropertyValues("career.agent.enabled=true").run(context -> {
            AutonomousCareerAgent agent = context.getBean(AutonomousCareerAgent.class);
            AgentReflection reflection = agent.runOnce(UUID.randomUUID());
            assertThat(reflection).isNotNull();
            assertThat(reflection.plan().isEmpty()).isTrue();
        });
    }

    @Test
    void endToEnd_missionAwareFlagOnButNoMissionBriefBeanAvailable_stillRunsWithoutError() {
        // career.mission.agent.enabled=true with no MissionAwareDailyBriefService bean registered
        // (mirrors production with career.agent.enabled on but Mission Engine's own beans absent
        // from this minimal test context) — must degrade gracefully, never fail context startup or the run.
        contextRunner.withPropertyValues("career.agent.enabled=true", "career.mission.agent.enabled=true").run(context -> {
            AutonomousCareerAgent agent = context.getBean(AutonomousCareerAgent.class);
            AgentReflection reflection = agent.runOnce(UUID.randomUUID());
            assertThat(reflection).isNotNull();
            assertThat(reflection.plan().isEmpty()).isTrue();
        });
    }

    @Test
    void missionAwareFlagAtDefault_agentStillConstructedWhenCareerAgentEnabled() {
        contextRunner.withPropertyValues("career.agent.enabled=true").run(context ->
                assertThat(context).hasSingleBean(AutonomousCareerAgent.class));
    }
}
