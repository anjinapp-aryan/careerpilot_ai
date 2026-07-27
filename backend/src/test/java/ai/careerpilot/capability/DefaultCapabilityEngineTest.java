package ai.careerpilot.capability;

import ai.careerpilot.mcp.McpCapability;
import ai.careerpilot.mcp.McpToolDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DefaultCapabilityEngine} — every break in the decision chain (no keyword match, {@code
 * tool.selection.enabled=false}, capability not registered, zero tools resolved) must produce a
 * {@code useToolCalling=false} decision, since {@link CapabilityAwareChatService} treats that as
 * "fall back to AiGatewayService unchanged."
 */
class DefaultCapabilityEngineTest {

    private final CapabilityRegistry registry = new InMemoryCapabilityRegistry();
    private final CapabilityResolver resolver = new KeywordCapabilityResolver();
    private final InMemoryCapabilityMetrics metrics = new InMemoryCapabilityMetrics();

    private McpToolDefinition githubTool() {
        return new McpToolDefinition("analyze_github_profile", "d", Map.of(), Map.of(), McpCapability.GITHUB, "github");
    }

    @Test
    void noKeywordMatch_fallsBackWithoutConsultingToolSelection() {
        ToolSelectionEngine toolSelection = def -> { throw new AssertionError("should not be called"); };
        DefaultCapabilityEngine engine = new DefaultCapabilityEngine(registry, resolver, toolSelection, metrics, true);

        CapabilityDecision decision = engine.analyze("what's the weather today?");

        assertThat(decision.useToolCalling()).isFalse();
        assertThat(decision.capabilityType()).isNull();
        assertThat(decision.reason()).contains("no capability keyword matched");
    }

    @Test
    void toolSelectionDisabled_fallsBackEvenWithKeywordMatch() {
        ToolSelectionEngine toolSelection = def -> List.of(githubTool());
        DefaultCapabilityEngine engine = new DefaultCapabilityEngine(registry, resolver, toolSelection, metrics, false);

        CapabilityDecision decision = engine.analyze("Analyse my GitHub profile.");

        assertThat(decision.useToolCalling()).isFalse();
        assertThat(decision.capabilityType()).isEqualTo(CapabilityType.GITHUB_REVIEW);
        assertThat(decision.reason()).contains("tool.selection.enabled=false");
    }

    @Test
    void noToolsResolved_fallsBackDespiteKeywordMatchAndSelectionEnabled() {
        ToolSelectionEngine toolSelection = def -> List.of();
        DefaultCapabilityEngine engine = new DefaultCapabilityEngine(registry, resolver, toolSelection, metrics, true);

        CapabilityDecision decision = engine.analyze("Analyse my GitHub profile.");

        assertThat(decision.useToolCalling()).isFalse();
        assertThat(decision.reason()).contains("no MCP tools registered");
    }

    @Test
    void keywordMatchAndToolsResolved_usesToolCalling() {
        ToolSelectionEngine toolSelection = def -> List.of(githubTool());
        DefaultCapabilityEngine engine = new DefaultCapabilityEngine(registry, resolver, toolSelection, metrics, true);

        CapabilityDecision decision = engine.analyze("Analyse my GitHub profile.");

        assertThat(decision.useToolCalling()).isTrue();
        assertThat(decision.capabilityType()).isEqualTo(CapabilityType.GITHUB_REVIEW);
        assertThat(decision.tools()).containsExactly(githubTool());
        assertThat(metrics.selectionCount("GITHUB_REVIEW")).isEqualTo(1);
    }

    @Test
    void capabilityNotRegistered_fallsBackGracefully() {
        CapabilityRegistry emptyRegistry = new CapabilityRegistry() {
            @Override public java.util.Optional<CapabilityDefinition> find(CapabilityType type) { return java.util.Optional.empty(); }
            @Override public List<CapabilityDefinition> all() { return List.of(); }
        };
        ToolSelectionEngine toolSelection = def -> List.of(githubTool());
        DefaultCapabilityEngine engine = new DefaultCapabilityEngine(emptyRegistry, resolver, toolSelection, metrics, true);

        CapabilityDecision decision = engine.analyze("Analyse my GitHub profile.");

        assertThat(decision.useToolCalling()).isFalse();
        assertThat(decision.reason()).contains("capability not registered");
    }
}
