package ai.careerpilot.capability;

import ai.careerpilot.mcp.McpCapability;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Phase 10.3 — the default {@link CapabilityRegistry}, pre-populated in its constructor with the
 * six capabilities named in the phase spec. "Register capabilities instead of hardcoded tools":
 * each entry names which {@link McpCapability} categories it draws from, not specific tool
 * names — {@link ToolSelectionEngine} resolves the actual tools against {@code McpRegistry} at
 * request time, so registering a new MCP server under an existing category is picked up with no
 * change here.
 */
public class InMemoryCapabilityRegistry implements CapabilityRegistry {

    private final Map<CapabilityType, CapabilityDefinition> definitions;

    public InMemoryCapabilityRegistry() {
        this.definitions = Map.ofEntries(
                Map.entry(CapabilityType.RESUME_ANALYSIS, new CapabilityDefinition(
                        CapabilityType.RESUME_ANALYSIS, "Analyze the user's resume",
                        Set.of(McpCapability.FILESYSTEM, McpCapability.MEMORY))),
                Map.entry(CapabilityType.JOB_RECOMMENDATION, new CapabilityDefinition(
                        CapabilityType.JOB_RECOMMENDATION, "Recommend jobs based on stored data",
                        Set.of(McpCapability.DATABASE, McpCapability.MEMORY))),
                Map.entry(CapabilityType.GITHUB_REVIEW, new CapabilityDefinition(
                        CapabilityType.GITHUB_REVIEW, "Review a GitHub profile/portfolio",
                        Set.of(McpCapability.GITHUB))),
                Map.entry(CapabilityType.CAREER_STRATEGY, new CapabilityDefinition(
                        CapabilityType.CAREER_STRATEGY, "Summarize career strategy and probabilities",
                        Set.of(McpCapability.DATABASE, McpCapability.MEMORY))),
                Map.entry(CapabilityType.INTERVIEW_PREPARATION, new CapabilityDefinition(
                        CapabilityType.INTERVIEW_PREPARATION, "Help prepare for an interview",
                        Set.of(McpCapability.MEMORY, McpCapability.KNOWLEDGE))),
                Map.entry(CapabilityType.LEARNING_HELP, new CapabilityDefinition(
                        CapabilityType.LEARNING_HELP, "Answer framework/API documentation questions",
                        Set.of(McpCapability.KNOWLEDGE))));
    }

    @Override
    public Optional<CapabilityDefinition> find(CapabilityType type) {
        return Optional.ofNullable(definitions.get(type));
    }

    @Override
    public List<CapabilityDefinition> all() {
        return List.copyOf(definitions.values());
    }
}
