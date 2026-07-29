package ai.careerpilot.runtime;

import ai.careerpilot.domain.WorkflowDefinition;
import ai.careerpilot.workflowregistry.WorkflowRegistryService;

import java.util.Optional;

/**
 * LangGraph Workflow Runtime — delegates entirely to the existing, pre-Phase-8
 * {@link WorkflowRegistryService#latestForType(String)} (the same non-throwing lookup the
 * Workflow Planner already uses). No new repository, no new query, no caching — a handful of
 * definition rows is cheap to hit directly.
 */
public class DefaultWorkflowRegistryAdapter implements WorkflowRegistryAdapter {

    private final WorkflowRegistryService registryService;

    public DefaultWorkflowRegistryAdapter(WorkflowRegistryService registryService) {
        this.registryService = registryService;
    }

    @Override
    public ResolvedWorkflowDefinition resolve(String workflowType) {
        Optional<WorkflowDefinition> definition = registryService.latestForType(workflowType);
        return definition.map(this::toResolved)
                .orElseThrow(() -> new WorkflowNotFoundException(workflowType));
    }

    private ResolvedWorkflowDefinition toResolved(WorkflowDefinition definition) {
        return new ResolvedWorkflowDefinition(definition.getWorkflowId(), definition.getName(),
                definition.getVersion(), definition.getWorkflowType(), definition.getStatus());
    }
}
