package ai.careerpilot.planner.execution;

import ai.careerpilot.capability.CapabilityRegistry;
import ai.careerpilot.capability.ToolSelectionEngine;
import ai.careerpilot.mcp.McpExecutor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Phase 11.3 — the only place any multi-capability execution bean is constructed, gated by the
 * single {@code multi.capability.enabled} flag (default {@code false}, matching the phase spec).
 * {@link CapabilityRegistry}/{@link ToolSelectionEngine} (Phase 10.3, own flag {@code
 * capability.engine.enabled}) and {@link McpExecutor} (Phase 10.2, own flag {@code mcp.enabled})
 * are injected via {@link ObjectProvider} — independent flags, so {@link
 * DefaultCapabilityExecutor} must degrade gracefully (see its own javadoc) if either layer is
 * off even while this one is on. Not wired into the Copilot or any controller yet — see the
 * package javadoc.
 */
@Configuration
public class MultiCapabilityExecutionConfig {

    @Bean
    @ConditionalOnProperty(prefix = "multi.capability", name = "enabled", havingValue = "true")
    public MultiCapabilityMetrics multiCapabilityMetrics() {
        return new InMemoryMultiCapabilityMetrics();
    }

    @Bean
    @ConditionalOnProperty(prefix = "multi.capability", name = "enabled", havingValue = "true")
    public CapabilityExecutor capabilityExecutor(ObjectProvider<CapabilityRegistry> registryProvider,
                                                  ObjectProvider<ToolSelectionEngine> toolSelectionProvider,
                                                  ObjectProvider<McpExecutor> mcpExecutorProvider,
                                                  MultiCapabilityMetrics metrics,
                                                  @Value("${multi.capability.max-retries:1}") int maxRetries) {
        return new DefaultCapabilityExecutor(registryProvider, toolSelectionProvider, mcpExecutorProvider, metrics, maxRetries);
    }

    @Bean
    @ConditionalOnProperty(prefix = "multi.capability", name = "enabled", havingValue = "true")
    public ParallelCapabilityExecutor parallelCapabilityExecutor(CapabilityExecutor executor, MultiCapabilityMetrics metrics) {
        return new DefaultParallelCapabilityExecutor(executor, metrics);
    }

    @Bean
    @ConditionalOnProperty(prefix = "multi.capability", name = "enabled", havingValue = "true")
    public ResultMerger resultMerger() {
        return new DefaultResultMerger();
    }

    @Bean
    @ConditionalOnProperty(prefix = "multi.capability", name = "enabled", havingValue = "true")
    public ExecutionCoordinator executionCoordinator(ParallelCapabilityExecutor stageExecutor, ResultMerger merger, MultiCapabilityMetrics metrics) {
        return new DefaultExecutionCoordinator(stageExecutor, merger, metrics);
    }
}
