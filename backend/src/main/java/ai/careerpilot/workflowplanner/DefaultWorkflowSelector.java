package ai.careerpilot.workflowplanner;

import ai.careerpilot.domain.WorkflowDefinition;
import ai.careerpilot.workflowregistry.WorkflowRegistryService;

import java.util.Optional;

/** Phase 8 — the only {@link WorkflowSelector}: a thin read against the existing Workflow Registry (Phase 4). No hardcoded workflows. */
public class DefaultWorkflowSelector implements WorkflowSelector {

    private final WorkflowRegistryService registry;

    public DefaultWorkflowSelector(WorkflowRegistryService registry) {
        this.registry = registry;
    }

    @Override
    public Optional<WorkflowDefinition> select(WorkflowType type) {
        return registry.latestForType(type.registryWorkflowType());
    }
}
