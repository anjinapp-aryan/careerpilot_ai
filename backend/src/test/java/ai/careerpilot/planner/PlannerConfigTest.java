package ai.careerpilot.planner;

import ai.careerpilot.intent.IntentConfidence;
import ai.careerpilot.intent.IntentResult;
import ai.careerpilot.intent.IntentType;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PlannerConfig} — dark-by-default guarantee, plus an end-to-end check that the real
 * wired bean graph actually produces a usable plan.
 */
class PlannerConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PlannerConfig.class);

    @Test
    void withFlagAtDefault_noPlannerBeansAreConstructed() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(CapabilityPlannerMetrics.class);
            assertThat(context).doesNotHaveBean(PlanOptimizer.class);
            assertThat(context).doesNotHaveBean(CapabilityPlanner.class);
        });
    }

    @Test
    void withFlagOn_allBeansConstructed() {
        contextRunner.withPropertyValues("capability.planner.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(CapabilityPlannerMetrics.class);
            assertThat(context).hasSingleBean(PlanOptimizer.class);
            assertThat(context).hasSingleBean(CapabilityPlanner.class);
        });
    }

    @Test
    void endToEnd_wiredPlannerProducesRealPlan() {
        contextRunner.withPropertyValues("capability.planner.enabled=true").run(context -> {
            CapabilityPlanner planner = context.getBean(CapabilityPlanner.class);
            IntentResult intentResult = new IntentResult(IntentType.GITHUB_ANALYSIS,
                    new IntentConfidence(0.9), List.of(), "test");

            CapabilityPlan plan = planner.plan(intentResult);

            assertThat(plan.isEmpty()).isFalse();
            assertThat(plan.executionOrder().stageCount()).isEqualTo(1);
        });
    }
}
