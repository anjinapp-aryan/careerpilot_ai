package ai.careerpilot.workflowplanner;

import ai.careerpilot.workflowregistry.WorkflowRegistryService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Phase 8 — the only place any Workflow Planner bean is constructed, gated by the single {@code
 * workflow.planner.enabled} flag (default {@code false}). Every bean here is stateless and
 * side-effect free (no I/O beyond the read-only {@link WorkflowRegistryService} call inside
 * {@link DefaultWorkflowSelector}) — flipping this flag on changes nothing on any request path,
 * since nothing outside this package calls {@link WorkflowPlanner} yet.
 */
@Configuration
public class WorkflowPlannerConfig {

    @Bean
    @ConditionalOnProperty(prefix = "workflow.planner", name = "enabled", havingValue = "true")
    public WorkflowStepTemplateProvider workflowStepTemplateProvider() {
        return new DefaultWorkflowStepTemplateProvider();
    }

    @Bean
    @ConditionalOnProperty(prefix = "workflow.planner", name = "enabled", havingValue = "true")
    public WorkflowDependencyResolver workflowDependencyResolver() {
        return new DefaultWorkflowDependencyResolver();
    }

    @Bean
    @ConditionalOnProperty(prefix = "workflow.planner", name = "enabled", havingValue = "true")
    public WorkflowEstimator workflowEstimator() {
        return new DefaultWorkflowEstimator();
    }

    @Bean
    @ConditionalOnProperty(prefix = "workflow.planner", name = "enabled", havingValue = "true")
    public WorkflowPlanFactory workflowPlanFactory() {
        return new DefaultWorkflowPlanFactory();
    }

    @Bean
    @ConditionalOnProperty(prefix = "workflow.planner", name = "enabled", havingValue = "true")
    public WorkflowValidator workflowValidator() {
        return new DefaultWorkflowValidator();
    }

    @Bean
    @ConditionalOnProperty(prefix = "workflow.planner", name = "enabled", havingValue = "true")
    public WorkflowSelector workflowSelector(WorkflowRegistryService registryService) {
        return new DefaultWorkflowSelector(registryService);
    }

    @Bean
    @ConditionalOnProperty(prefix = "workflow.planner", name = "enabled", havingValue = "true")
    public WorkflowPlanner workflowPlanner(WorkflowSelector selector, WorkflowStepTemplateProvider steps,
                                            WorkflowDependencyResolver dependencyResolver, WorkflowEstimator estimator,
                                            WorkflowPlanFactory planFactory, WorkflowValidator validator) {
        return new DefaultWorkflowPlanner(selector, steps, dependencyResolver, estimator, planFactory, validator);
    }
}
