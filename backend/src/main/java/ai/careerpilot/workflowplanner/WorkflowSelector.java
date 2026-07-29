package ai.careerpilot.workflowplanner;

import ai.careerpilot.domain.WorkflowDefinition;

import java.util.Optional;

/**
 * Phase 8 — resolves a {@link WorkflowType} to its registered {@link WorkflowDefinition} by
 * asking the existing Workflow Registry (Phase 4, {@code WorkflowRegistryService}) — the planner
 * never hardcodes a workflow's existence, version, or required capabilities.
 */
public interface WorkflowSelector {

    Optional<WorkflowDefinition> select(WorkflowType type);
}
