package ai.careerpilot.planner.execution;

import ai.careerpilot.capability.CapabilityDefinition;
import ai.careerpilot.capability.CapabilityRegistry;
import ai.careerpilot.capability.CapabilityType;
import ai.careerpilot.capability.ToolSelectionEngine;
import ai.careerpilot.mcp.McpCapability;
import ai.careerpilot.mcp.McpExecutionContext;
import ai.careerpilot.mcp.McpExecutor;
import ai.careerpilot.mcp.McpToolDefinition;
import ai.careerpilot.mcp.McpToolResult;
import ai.careerpilot.planner.CapabilityPriority;
import ai.careerpilot.planner.CapabilityStep;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultCapabilityExecutorTest {

    private final InMemoryMultiCapabilityMetrics metrics = new InMemoryMultiCapabilityMetrics();

    @SuppressWarnings("unchecked")
    private <T> ObjectProvider<T> providerFor(T value) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(value);
        return p;
    }

    private McpExecutionContext context() {
        return new McpExecutionContext(UUID.randomUUID(), null, null, "trace", Duration.ofSeconds(5), Map.of());
    }

    private CapabilityStep step() {
        return new CapabilityStep(CapabilityType.GITHUB_REVIEW, CapabilityPriority.HIGH);
    }

    private CapabilityRegistry registryWithGithubTool() {
        CapabilityRegistry registry = mock(CapabilityRegistry.class);
        CapabilityDefinition def = new CapabilityDefinition(CapabilityType.GITHUB_REVIEW, "d", Set.of(McpCapability.GITHUB));
        when(registry.find(CapabilityType.GITHUB_REVIEW)).thenReturn(Optional.of(def));
        return registry;
    }

    private ToolSelectionEngine toolSelectionWithOneTool() {
        ToolSelectionEngine engine = mock(ToolSelectionEngine.class);
        McpToolDefinition tool = new McpToolDefinition("analyze_github_profile", "d", Map.of(), Map.of(), McpCapability.GITHUB, "github");
        when(engine.selectTools(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(tool));
        return engine;
    }

    @Test
    void platformUnavailable_returnsFailedResultRatherThanThrowing() {
        DefaultCapabilityExecutor executor = new DefaultCapabilityExecutor(
                providerFor(null), providerFor(null), providerFor(null), metrics, 0);

        ExecutionResult result = executor.execute(step(), context());

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("capability platform unavailable");
    }

    @Test
    void capabilityNotRegistered_returnsFailedResult() {
        CapabilityRegistry registry = mock(CapabilityRegistry.class);
        when(registry.find(CapabilityType.GITHUB_REVIEW)).thenReturn(Optional.empty());
        DefaultCapabilityExecutor executor = new DefaultCapabilityExecutor(
                providerFor(registry), providerFor(toolSelectionWithOneTool()), providerFor(mock(McpExecutor.class)), metrics, 0);

        ExecutionResult result = executor.execute(step(), context());

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("not registered");
    }

    @Test
    void successfulExecution_returnsSuccessWithOneAttempt() {
        McpExecutor mcpExecutor = mock(McpExecutor.class);
        when(mcpExecutor.execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Mono.just(McpToolResult.ok("ok")));

        DefaultCapabilityExecutor executor = new DefaultCapabilityExecutor(
                providerFor(registryWithGithubTool()), providerFor(toolSelectionWithOneTool()), providerFor(mcpExecutor), metrics, 2);

        ExecutionResult result = executor.execute(step(), context());

        assertThat(result.success()).isTrue();
        assertThat(result.attempts()).isEqualTo(1);
        assertThat(result.toolResults()).containsKey("analyze_github_profile");
    }

    @Test
    void toolThrows_isCapturedAsFailedResultWithoutPropagating() {
        McpExecutor mcpExecutor = mock(McpExecutor.class);
        when(mcpExecutor.execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new RuntimeException("network down"));

        DefaultCapabilityExecutor executor = new DefaultCapabilityExecutor(
                providerFor(registryWithGithubTool()), providerFor(toolSelectionWithOneTool()), providerFor(mcpExecutor), metrics, 0);

        ExecutionResult result = executor.execute(step(), context());

        assertThat(result.success()).isFalse();
        assertThat(result.toolResults().get("analyze_github_profile").success()).isFalse();
    }

    @Test
    void retriesUpToMaxRetries_thenSucceedsOnFinalAttempt() {
        McpExecutor mcpExecutor = mock(McpExecutor.class);
        AtomicInteger calls = new AtomicInteger();
        when(mcpExecutor.execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> calls.incrementAndGet() < 3
                        ? Mono.just(McpToolResult.failed("transient"))
                        : Mono.just(McpToolResult.ok("ok")));

        DefaultCapabilityExecutor executor = new DefaultCapabilityExecutor(
                providerFor(registryWithGithubTool()), providerFor(toolSelectionWithOneTool()), providerFor(mcpExecutor), metrics, 3);

        ExecutionResult result = executor.execute(step(), context());

        assertThat(result.success()).isTrue();
        assertThat(result.attempts()).isEqualTo(3);
        assertThat(metrics.retryCount("GITHUB_REVIEW")).isEqualTo(2);
    }

    @Test
    void exhaustsRetries_returnsFailedResultAndRecordsPartialFailure() {
        McpExecutor mcpExecutor = mock(McpExecutor.class);
        when(mcpExecutor.execute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Mono.just(McpToolResult.failed("always fails")));

        DefaultCapabilityExecutor executor = new DefaultCapabilityExecutor(
                providerFor(registryWithGithubTool()), providerFor(toolSelectionWithOneTool()), providerFor(mcpExecutor), metrics, 2);

        ExecutionResult result = executor.execute(step(), context());

        assertThat(result.success()).isFalse();
        assertThat(result.attempts()).isEqualTo(3);
        assertThat(metrics.partialFailureCount("GITHUB_REVIEW")).isEqualTo(1);
    }
}
