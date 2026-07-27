package ai.careerpilot.planner.execution;

import ai.careerpilot.capability.CapabilityType;
import ai.careerpilot.planner.CapabilityDependencies;
import ai.careerpilot.planner.CapabilityPlan;
import ai.careerpilot.planner.CapabilityPriority;
import ai.careerpilot.planner.CapabilityStep;
import ai.careerpilot.planner.ExecutionOrder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionGraphTest {

    @Test
    void resolvesBareTypesBackIntoFullSteps() {
        CapabilityStep career = new CapabilityStep(CapabilityType.CAREER_STRATEGY, CapabilityPriority.HIGH);
        CapabilityStep jobs = new CapabilityStep(CapabilityType.JOB_RECOMMENDATION, CapabilityPriority.MEDIUM);
        CapabilityPlan plan = new CapabilityPlan(null, List.of(career, jobs),
                new CapabilityDependencies(Map.of(CapabilityType.JOB_RECOMMENDATION, Set.of(CapabilityType.CAREER_STRATEGY))),
                new ExecutionOrder(List.of(List.of(CapabilityType.CAREER_STRATEGY), List.of(CapabilityType.JOB_RECOMMENDATION))),
                "test");

        ExecutionGraph graph = ExecutionGraph.from(plan);

        assertThat(graph.stages()).hasSize(2);
        assertThat(graph.stages().get(0)).containsExactly(career);
        assertThat(graph.stages().get(1)).containsExactly(jobs);
    }

    @Test
    void emptyPlanProducesEmptyGraph() {
        ExecutionGraph graph = ExecutionGraph.from(CapabilityPlan.empty("no intent matched"));
        assertThat(graph.isEmpty()).isTrue();
    }
}
