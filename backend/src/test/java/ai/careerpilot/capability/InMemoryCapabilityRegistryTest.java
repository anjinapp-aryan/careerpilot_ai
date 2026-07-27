package ai.careerpilot.capability;

import ai.careerpilot.mcp.McpCapability;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryCapabilityRegistryTest {

    private final InMemoryCapabilityRegistry registry = new InMemoryCapabilityRegistry();

    @Test
    void allSixSpecCapabilitiesArePreRegistered() {
        assertThat(registry.all()).hasSize(6);
        for (CapabilityType type : CapabilityType.values()) {
            assertThat(registry.find(type)).isPresent();
        }
    }

    @Test
    void resumeAnalysisRequiresFilesystemAndMemory() {
        CapabilityDefinition def = registry.find(CapabilityType.RESUME_ANALYSIS).orElseThrow();
        assertThat(def.requiredMcpCapabilities()).containsExactlyInAnyOrder(McpCapability.FILESYSTEM, McpCapability.MEMORY);
    }

    @Test
    void githubReviewRequiresOnlyGithub() {
        CapabilityDefinition def = registry.find(CapabilityType.GITHUB_REVIEW).orElseThrow();
        assertThat(def.requiredMcpCapabilities()).containsExactly(McpCapability.GITHUB);
    }

    @Test
    void learningHelpRequiresOnlyKnowledge() {
        CapabilityDefinition def = registry.find(CapabilityType.LEARNING_HELP).orElseThrow();
        assertThat(def.requiredMcpCapabilities()).containsExactly(McpCapability.KNOWLEDGE);
    }
}
