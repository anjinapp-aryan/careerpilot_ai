package ai.careerpilot.mcp;

import ai.careerpilot.mcp.security.McpAuditor;
import ai.careerpilot.mcp.security.McpAuthenticationProvider;
import ai.careerpilot.mcp.security.McpAuthorizationProvider;
import ai.careerpilot.mcp.springai.SpringAiMcpBridge;
import ai.careerpilot.mcp.springai.ToolCallingAdapter;
import ai.careerpilot.mcp.tool.McpToolHandlerRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link McpConfig} is the only place any MCP platform-core bean is constructed, gated by
 * {@code mcp.enabled} / {@code mcp.health.enabled}. This verifies the dark-by-default guarantee:
 * with no properties set (i.e. every flag at its {@code false} default), none of the platform
 * beans exist in the context — matching every other dark-shipped phase's "flag off = beans
 * literally aren't constructed" convention (see ai.careerpilot.ai.springai.SpringAiConfig for
 * the precedent this mirrors). Updated for Phase 10.2: {@link McpMetrics}'s default
 * implementation changed from {@link NoopMcpMetrics} to {@link InMemoryMcpMetrics}, and several
 * new execution-plumbing beans (handler registry, executor, security defaults, Spring AI
 * bridge/adapter) were added, all under the same {@code mcp.enabled} gate.
 */
class McpConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(McpConfig.class);

    @Test
    void withNoPropertiesSet_noMcpBeansAreConstructed() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(McpRegistry.class);
            assertThat(context).doesNotHaveBean(McpMetrics.class);
            assertThat(context).doesNotHaveBean(McpHealthManager.class);
            assertThat(context).doesNotHaveBean(McpToolHandlerRegistry.class);
            assertThat(context).doesNotHaveBean(McpExecutor.class);
            assertThat(context).doesNotHaveBean(McpAuthorizationProvider.class);
            assertThat(context).doesNotHaveBean(McpAuthenticationProvider.class);
            assertThat(context).doesNotHaveBean(McpAuditor.class);
            assertThat(context).doesNotHaveBean(ToolCallingAdapter.class);
            assertThat(context).doesNotHaveBean(SpringAiMcpBridge.class);
        });
    }

    @Test
    void mcpEnabledTrue_constructsPlatformCoreBeans_butNotHealthManager() {
        contextRunner.withPropertyValues("mcp.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(McpRegistry.class);
            assertThat(context).hasSingleBean(McpMetrics.class);
            assertThat(context).hasSingleBean(McpToolHandlerRegistry.class);
            assertThat(context).hasSingleBean(McpExecutor.class);
            assertThat(context).hasSingleBean(McpAuthorizationProvider.class);
            assertThat(context).hasSingleBean(McpAuthenticationProvider.class);
            assertThat(context).hasSingleBean(McpAuditor.class);
            assertThat(context).hasSingleBean(ToolCallingAdapter.class);
            assertThat(context).hasSingleBean(SpringAiMcpBridge.class);
            assertThat(context).doesNotHaveBean(McpHealthManager.class);
            assertThat(context.getBean(McpRegistry.class)).isInstanceOf(InMemoryMcpRegistry.class);
            assertThat(context.getBean(McpMetrics.class)).isInstanceOf(InMemoryMcpMetrics.class);
        });
    }

    @Test
    void mcpHealthEnabledTrue_constructsHealthManagerIndependentlyOfMcpEnabled() {
        contextRunner.withPropertyValues("mcp.health.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(McpHealthManager.class);
            assertThat(context).doesNotHaveBean(McpRegistry.class);
            assertThat(context.getBean(McpHealthManager.class)).isInstanceOf(InMemoryMcpHealthManager.class);
        });
    }

    @Test
    void allFlagsTrue_constructsEveryPlatformCoreBean() {
        contextRunner.withPropertyValues("mcp.enabled=true", "mcp.health.enabled=true", "mcp.discovery.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(McpRegistry.class);
                    assertThat(context).hasSingleBean(McpMetrics.class);
                    assertThat(context).hasSingleBean(McpHealthManager.class);
                    assertThat(context).hasSingleBean(McpExecutor.class);
                });
    }
}
