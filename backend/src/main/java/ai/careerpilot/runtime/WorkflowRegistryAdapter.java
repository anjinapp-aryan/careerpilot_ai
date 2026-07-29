package ai.careerpilot.runtime;

import ai.careerpilot.workflowplanner.WorkflowType;

/**
 * LangGraph Workflow Runtime — resolves a plain {@code workflow_type} key to its current Workflow
 * Registry (Phase 4) definition. The only seam between this package and {@code
 * ai.careerpilot.workflowregistry} — deliberately named "adapter," not "service" or "client,"
 * since it does no business logic of its own, purely translating between the registry's entity
 * shape and this runtime's {@link ResolvedWorkflowDefinition}.
 *
 * <h2>Phase 10A — decoupled from {@code WorkflowType}</h2>
 * {@link #resolve(String)} is now the primary method: any registered {@code workflow_type} string
 * resolves, whether or not it has a corresponding {@code ai.careerpilot.workflowplanner.WorkflowType}
 * enum value. {@link #resolve(WorkflowType)} remains as a convenience default method for callers
 * that already have a {@link WorkflowType} (the Mission Execution Engine flow) — it's no longer
 * the only way in.
 */
public interface WorkflowRegistryAdapter {

    /**
     * @throws WorkflowNotFoundException if no active definition exists for {@code workflowType}
     */
    ResolvedWorkflowDefinition resolve(String workflowType);

    /** Convenience overload for callers that already have a {@link WorkflowType}. */
    default ResolvedWorkflowDefinition resolve(WorkflowType type) {
        return resolve(type.registryWorkflowType());
    }
}
