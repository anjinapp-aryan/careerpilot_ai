package ai.careerpilot.runtime;

/**
 * LangGraph Workflow Runtime — the small, read-only projection of a Workflow Registry (Phase 4)
 * {@code WorkflowDefinition} that {@link WorkflowRegistryAdapter} resolves. Deliberately does not
 * carry {@code agentConfigurationJson}/{@code requiredCapabilitiesJson}/{@code requiredToolsJson}
 * — this runtime doesn't interpret those (that's the Capability Layer's job, once wired); it only
 * needs the identifier/name/version/status to build an execution context and populate the result.
 */
public record ResolvedWorkflowDefinition(String workflowId, String name, String version, String workflowType,
                                          String status) {
}
