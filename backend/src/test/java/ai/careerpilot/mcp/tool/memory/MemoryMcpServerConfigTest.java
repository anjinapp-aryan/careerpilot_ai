package ai.careerpilot.mcp.tool.memory;

import ai.careerpilot.domain.CareerDecisionMemory;
import ai.careerpilot.mcp.InMemoryMcpMetrics;
import ai.careerpilot.mcp.InMemoryMcpRegistry;
import ai.careerpilot.mcp.McpExecutionContext;
import ai.careerpilot.mcp.McpServerDefinition;
import ai.careerpilot.mcp.tool.McpToolHandler;
import ai.careerpilot.mcp.tool.McpToolHandlerRegistry;
import ai.careerpilot.memory.CareerMemoryService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link MemoryMcpServerConfig} — verifies registration and that both handlers delegate to the
 * existing {@link CareerMemoryService} (mocked here) rather than any new memory store.
 */
class MemoryMcpServerConfigTest {

    private final InMemoryMcpRegistry registry = new InMemoryMcpRegistry();
    private final McpToolHandlerRegistry handlers = new McpToolHandlerRegistry();
    private final InMemoryMcpMetrics metrics = new InMemoryMcpMetrics();
    private final CareerMemoryService memoryService = mock(CareerMemoryService.class);
    private final MemoryMcpServerConfig config = new MemoryMcpServerConfig();

    @Test
    void registersServerAndBothTools() {
        McpServerDefinition server = config.memoryMcpServer(registry, handlers, metrics, memoryService);

        assertThat(server.name()).isEqualTo("memory");
        assertThat(registry.findTool("get_career_memory_summary")).isPresent();
        assertThat(registry.findTool("get_relevant_memories")).isPresent();
    }

    @Test
    void summaryHandlerDelegatesToCareerMemoryService() {
        UUID userId = UUID.randomUUID();
        when(memoryService.summaryFor(userId)).thenReturn(
                new CareerMemoryService.MemorySummary(5, 3, 1, 0, 0, 0.82, Instant.now()));

        config.memoryMcpServer(registry, handlers, metrics, memoryService);
        McpToolHandler handler = handlers.find("get_career_memory_summary").orElseThrow();
        McpExecutionContext context = new McpExecutionContext(userId, null, null, "trace", Duration.ofSeconds(5), Map.of());

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) handler.handle(Map.of(), context);

        assertThat(result.get("totalMemories")).isEqualTo(5);
        assertThat(result.get("averageConfidence")).isEqualTo(0.82);
    }

    @Test
    void relevantMemoriesHandlerUsesCategoryFilterWhenProvided() {
        UUID userId = UUID.randomUUID();
        CareerDecisionMemory memory = CareerDecisionMemory.builder()
                .userId(userId).decisionType("target_role").category("career_goal")
                .value("Staff Engineer").confidence(new BigDecimal("0.9")).source("copilot").build();
        when(memoryService.relevantFor(userId, "career_goal", 5)).thenReturn(List.of(memory));

        config.memoryMcpServer(registry, handlers, metrics, memoryService);
        McpToolHandler handler = handlers.find("get_relevant_memories").orElseThrow();
        McpExecutionContext context = new McpExecutionContext(userId, null, null, "trace", Duration.ofSeconds(5), Map.of());

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) handler.handle(Map.of("category", "career_goal"), context);

        assertThat(result.get("count")).isEqualTo(1);
    }

    @Test
    void handlerDegradesGracefullyWhenNoAuthenticatedUser() {
        config.memoryMcpServer(registry, handlers, metrics, memoryService);
        McpToolHandler handler = handlers.find("get_career_memory_summary").orElseThrow();
        McpExecutionContext context = new McpExecutionContext(null, null, null, "trace", Duration.ofSeconds(5), Map.of());

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) handler.handle(Map.of(), context);

        assertThat(result.get("available")).isEqualTo(false);
    }
}
