package ai.careerpilot.runtime;

import ai.careerpilot.agent.AgentServiceClient;
import ai.careerpilot.workflowregistry.WorkflowRegistryService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LangGraph Workflow Runtime — the only place any runtime bean is constructed, gated by the
 * single {@code runtime.enabled} flag (default {@code false}). {@link AgentServiceClient} and
 * {@link WorkflowRegistryService} are pre-existing, unconditionally-constructed beans (not behind
 * any flag) — injecting them directly here is safe; only the runtime's own beans are dark. With
 * the flag off, none of this package's beans exist and nothing outside {@code
 * ai.careerpilot.runtime} references {@link WorkflowRuntime} — flipping it on changes nothing on
 * any request path, since nothing calls {@link WorkflowRuntime#execute} yet either way (see the
 * package-info for the ownership note on the future Mission Orchestrator wiring).
 */
@Configuration
@ConditionalOnProperty(prefix = "runtime", name = "enabled", havingValue = "true")
public class WorkflowRuntimeConfiguration {

    @Bean
    public ExecutionRequestValidator executionRequestValidator() {
        return new DefaultExecutionRequestValidator();
    }

    @Bean
    public WorkflowRegistryAdapter workflowRegistryAdapter(WorkflowRegistryService registryService) {
        return new DefaultWorkflowRegistryAdapter(registryService);
    }

    @Bean
    public WorkflowContextFactory workflowContextFactory() {
        return new DefaultWorkflowContextFactory();
    }

    @Bean
    public WorkflowStateFactory workflowStateFactory() {
        return new DefaultWorkflowStateFactory();
    }

    @Bean
    public WorkflowExecutor workflowExecutor(AgentServiceClient agentServiceClient) {
        return new LangGraphWorkflowExecutor(agentServiceClient);
    }

    @Bean
    public WorkflowLifecycleManager workflowLifecycleManager() {
        return new DefaultWorkflowLifecycleManager();
    }

    @Bean
    public WorkflowResultMapper workflowResultMapper() {
        return new DefaultWorkflowResultMapper();
    }

    @Bean
    public WorkflowMetrics workflowMetrics() {
        return new InMemoryWorkflowMetrics();
    }

    @Bean
    public WorkflowRuntime workflowRuntime(ExecutionRequestValidator validator, WorkflowRegistryAdapter registryAdapter,
                                            WorkflowContextFactory contextFactory, WorkflowStateFactory stateFactory,
                                            WorkflowExecutor executor, WorkflowLifecycleManager lifecycleManager,
                                            WorkflowResultMapper resultMapper, WorkflowMetrics metrics) {
        return new DefaultWorkflowRuntime(validator, registryAdapter, contextFactory, stateFactory, executor,
                lifecycleManager, resultMapper, metrics);
    }
}
