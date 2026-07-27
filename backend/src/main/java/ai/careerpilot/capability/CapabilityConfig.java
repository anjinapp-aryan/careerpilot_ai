package ai.careerpilot.capability;

import ai.careerpilot.ai.AiGatewayService;
import ai.careerpilot.mcp.McpExecutor;
import ai.careerpilot.mcp.McpRegistry;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Phase 10.3 — the only place any capability-orchestration bean is constructed. Everything is
 * gated behind the master switch {@code capability.engine.enabled} (default {@code false}) — with
 * it off, none of these beans exist, so {@link AiGatewayService} (unmodified, still autowired
 * exactly as before this phase) remains the only chat path in the application, byte-for-byte
 * identical to pre-Phase-10.3 behavior.
 *
 * <p>The other three flags ({@code tool.selection.enabled}, {@code
 * parallel.tool.execution.enabled}, {@code spring.ai.tool.calling.enabled}) are read at runtime
 * (via {@code @Value}, not additional {@code @ConditionalOnProperty} bean gates) because they
 * control *behavior inside* an already-dark-by-default layer, not whether that layer exists —
 * flipping {@code capability.engine.enabled=true} alone, with the other three still {@code
 * false}, still results in every request falling back to {@code AiGatewayService.chat(...)}
 * (see {@link DefaultCapabilityEngine} and {@link CapabilityAwareChatService} for exactly where
 * each flag is consulted).
 *
 * <p>{@link McpRegistry}/{@link McpExecutor}/{@link ChatModel} are all optional dependencies
 * here ({@link ObjectProvider}) since they belong to Phase 10.1/10.2 (own flag: {@code
 * mcp.enabled}) and Phase 9.1 (own flag: {@code ai.springai.foundation.enabled}) respectively —
 * independent flags, so this layer must degrade gracefully if either is off even while {@code
 * capability.engine.enabled=true}.
 */
@Configuration
public class CapabilityConfig {

    @Bean
    @ConditionalOnProperty(prefix = "capability.engine", name = "enabled", havingValue = "true")
    public CapabilityRegistry capabilityRegistry() {
        return new InMemoryCapabilityRegistry();
    }

    @Bean
    @ConditionalOnProperty(prefix = "capability.engine", name = "enabled", havingValue = "true")
    public CapabilityResolver capabilityResolver() {
        return new KeywordCapabilityResolver();
    }

    @Bean
    @ConditionalOnProperty(prefix = "capability.engine", name = "enabled", havingValue = "true")
    public CapabilityMetrics capabilityMetrics() {
        return new InMemoryCapabilityMetrics();
    }

    @Bean
    @ConditionalOnProperty(prefix = "capability.engine", name = "enabled", havingValue = "true")
    public ToolSelectionEngine toolSelectionEngine(ObjectProvider<McpRegistry> mcpRegistryProvider) {
        return new DefaultToolSelectionEngine(mcpRegistryProvider);
    }

    @Bean
    @ConditionalOnProperty(prefix = "capability.engine", name = "enabled", havingValue = "true")
    public CapabilityEngine capabilityEngine(CapabilityRegistry registry,
                                              CapabilityResolver resolver,
                                              ToolSelectionEngine toolSelectionEngine,
                                              CapabilityMetrics metrics,
                                              @Value("${tool.selection.enabled:false}") boolean toolSelectionEnabled) {
        return new DefaultCapabilityEngine(registry, resolver, toolSelectionEngine, metrics, toolSelectionEnabled);
    }

    @Bean
    @ConditionalOnProperty(prefix = "capability.engine", name = "enabled", havingValue = "true")
    public CapabilityAwareChatService capabilityAwareChatService(
            AiGatewayService aiGatewayService,
            CapabilityEngine capabilityEngine,
            ObjectProvider<McpExecutor> mcpExecutorProvider,
            ObjectProvider<ChatModel> springAiChatModelProvider,
            CapabilityMetrics metrics,
            @Value("${parallel.tool.execution.enabled:false}") boolean parallelExecutionEnabled,
            @Value("${spring.ai.tool.calling.enabled:false}") boolean springAiToolCallingEnabled) {
        return new CapabilityAwareChatService(aiGatewayService, capabilityEngine, mcpExecutorProvider,
                springAiChatModelProvider, metrics, parallelExecutionEnabled, springAiToolCallingEnabled);
    }
}
