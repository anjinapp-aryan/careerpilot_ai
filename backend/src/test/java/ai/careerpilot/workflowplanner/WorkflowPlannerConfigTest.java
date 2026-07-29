package ai.careerpilot.workflowplanner;

import ai.careerpilot.domain.WorkflowDefinition;
import ai.careerpilot.workflowregistry.WorkflowRegistryService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link WorkflowPlannerConfig} — the master {@code workflow.planner.enabled} flag must gate
 * every bean here. With it on and a real (test-provided) {@link WorkflowRegistryService}, the
 * fully-wired {@link WorkflowPlanner} produces a real plan end-to-end — the closest thing to an
 * integration test this package has, without needing a real database (the registry is mocked,
 * matching the established {@code CapabilityConfigTest} pattern).
 */
class WorkflowPlannerConfigTest {

    @Configuration
    static class MockRegistry {
        @Bean
        WorkflowRegistryService workflowRegistryService() {
            WorkflowRegistryService registry = mock(WorkflowRegistryService.class);
            WorkflowDefinition def = WorkflowDefinition.builder()
                    .workflowId("RESUME_OPTIMIZATION_V1").version("v1")
                    .requiredCapabilitiesJson("[\"RESUME_ANALYSIS\"]").requiredToolsJson("[]").build();
            when(registry.latestForType("RESUME_OPTIMIZATION")).thenReturn(Optional.of(def));
            when(registry.latestForType("PORTFOLIO")).thenReturn(Optional.empty());
            return registry;
        }
    }

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(MockRegistry.class, WorkflowPlannerConfig.class);

    @Test
    void withFlagAtDefault_noPlannerBeansAreConstructed() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(WorkflowPlanner.class);
            assertThat(context).doesNotHaveBean(WorkflowSelector.class);
            assertThat(context).doesNotHaveBean(WorkflowStepTemplateProvider.class);
            assertThat(context).doesNotHaveBean(WorkflowEstimator.class);
        });
    }

    @Test
    void withFlagOn_allBeansConstructed() {
        contextRunner.withPropertyValues("workflow.planner.enabled=true").run(context ->
                assertThat(context).hasSingleBean(WorkflowPlanner.class));
    }

    @Test
    void endToEnd_realPlanIsProducedForARegisteredType() {
        contextRunner.withPropertyValues("workflow.planner.enabled=true").run(context -> {
            WorkflowPlanner planner = context.getBean(WorkflowPlanner.class);
            WorkflowPlanRequest request = new WorkflowPlanRequest(UUID.randomUUID(), WorkflowType.RESUME);

            WorkflowPlan plan = planner.plan(request);

            assertThat(plan.workflowType()).isEqualTo(WorkflowType.RESUME);
            assertThat(plan.sequentialSteps()).extracting(WorkflowStep::stepName)
                    .containsExactly("Analyze Resume", "ATS Optimization", "Generate Improvements", "Approval");
            assertThat(plan.approvalRequired()).isTrue();
            assertThat(plan.estimate()).isNotNull();
            assertThat(plan.futureLangGraphGraphId()).isEqualTo("RESUME_GRAPH_V1");
        });
    }

    @Test
    void endToEnd_throwsForAnUnregisteredType() {
        contextRunner.withPropertyValues("workflow.planner.enabled=true").run(context -> {
            WorkflowPlanner planner = context.getBean(WorkflowPlanner.class);
            WorkflowPlanRequest request = new WorkflowPlanRequest(UUID.randomUUID(), WorkflowType.PORTFOLIO);

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> planner.plan(request))
                    .isInstanceOf(WorkflowPlanningException.class);
        });
    }
}
