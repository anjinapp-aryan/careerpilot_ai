package ai.careerpilot.capability;

import ai.careerpilot.mcp.InMemoryMcpRegistry;
import ai.careerpilot.mcp.McpCapability;
import ai.careerpilot.mcp.McpRegistry;
import ai.careerpilot.mcp.McpToolDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultToolSelectionEngineTest {

    @SuppressWarnings("unchecked")
    private ObjectProvider<McpRegistry> provider(McpRegistry registry) {
        ObjectProvider<McpRegistry> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(registry);
        return p;
    }

    @Test
    void returnsEmptyListWhenMcpRegistryAbsent() {
        DefaultToolSelectionEngine engine = new DefaultToolSelectionEngine(provider(null));
        CapabilityDefinition def = new CapabilityDefinition(CapabilityType.GITHUB_REVIEW, "d", Set.of(McpCapability.GITHUB));

        assertThat(engine.selectTools(def)).isEmpty();
    }

    @Test
    void selectsToolsAcrossAllRequiredCapabilities() {
        InMemoryMcpRegistry registry = new InMemoryMcpRegistry();
        registry.registerTool(new McpToolDefinition("get_latest_resume_document", "d", Map.of(), Map.of(), McpCapability.FILESYSTEM, "filesystem"));
        registry.registerTool(new McpToolDefinition("get_career_memory_summary", "d", Map.of(), Map.of(), McpCapability.MEMORY, "memory"));
        registry.registerTool(new McpToolDefinition("analyze_github_profile", "d", Map.of(), Map.of(), McpCapability.GITHUB, "github"));

        DefaultToolSelectionEngine engine = new DefaultToolSelectionEngine(provider(registry));
        CapabilityDefinition def = new CapabilityDefinition(CapabilityType.RESUME_ANALYSIS, "d", Set.of(McpCapability.FILESYSTEM, McpCapability.MEMORY));

        List<McpToolDefinition> tools = engine.selectTools(def);

        assertThat(tools).extracting(McpToolDefinition::toolName)
                .containsExactlyInAnyOrder("get_latest_resume_document", "get_career_memory_summary");
    }

    @Test
    void returnsEmptyListWhenNoToolsRegisteredForRequiredCapabilities() {
        InMemoryMcpRegistry registry = new InMemoryMcpRegistry();
        DefaultToolSelectionEngine engine = new DefaultToolSelectionEngine(provider(registry));
        CapabilityDefinition def = new CapabilityDefinition(CapabilityType.GITHUB_REVIEW, "d", Set.of(McpCapability.GITHUB));

        assertThat(engine.selectTools(def)).isEmpty();
    }
}
