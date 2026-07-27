package ai.careerpilot.planner;

import ai.careerpilot.capability.CapabilityType;
import ai.careerpilot.intent.IntentConfidence;
import ai.careerpilot.intent.IntentResult;
import ai.careerpilot.intent.IntentType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultCapabilityPlannerTest {

    private final InMemoryCapabilityPlannerMetrics metrics = new InMemoryCapabilityPlannerMetrics();
    private final DefaultPlanOptimizer optimizer = new DefaultPlanOptimizer(metrics);
    private final DefaultCapabilityPlanner planner = new DefaultCapabilityPlanner(optimizer, metrics);

    private IntentResult resultFor(IntentType type) {
        return new IntentResult(type, new IntentConfidence(0.9), List.of(), "test");
    }

    @Test
    void nullIntentResult_producesEmptyPlan() {
        CapabilityPlan plan = planner.plan(null);
        assertThat(plan.isEmpty()).isTrue();
        assertThat(plan.intentType()).isNull();
    }

    @Test
    void noMatchedIntent_producesEmptyPlan() {
        IntentResult noMatch = IntentResult.none("no capability keyword matched");
        CapabilityPlan plan = planner.plan(noMatch);
        assertThat(plan.isEmpty()).isTrue();
    }

    @Test
    void singleCapabilityIntent_producesOneStepPlan() {
        CapabilityPlan plan = planner.plan(resultFor(IntentType.GITHUB_ANALYSIS));

        assertThat(plan.steps()).hasSize(1);
        assertThat(plan.steps().get(0).type()).isEqualTo(CapabilityType.GITHUB_REVIEW);
        assertThat(plan.executionOrder().stageCount()).isEqualTo(1);
    }

    @Test
    void executiveCoachIntent_producesTwoStepPlanWithDependencyOrdering() {
        CapabilityPlan plan = planner.plan(resultFor(IntentType.EXECUTIVE_COACH));

        assertThat(plan.steps()).hasSize(2);
        assertThat(plan.dependencies().dependenciesOf(CapabilityType.JOB_RECOMMENDATION))
                .containsExactly(CapabilityType.CAREER_STRATEGY);
        assertThat(plan.executionOrder().stages()).hasSize(2);
        assertThat(plan.executionOrder().stages().get(0)).containsExactly(CapabilityType.CAREER_STRATEGY);
        assertThat(plan.executionOrder().stages().get(1)).containsExactly(CapabilityType.JOB_RECOMMENDATION);
    }

    @Test
    void recordsMetricsForEveryPlan() {
        planner.plan(resultFor(IntentType.GITHUB_ANALYSIS));
        assertThat(metrics.avgPlanSize()).isEqualTo(1.0);
    }
}
