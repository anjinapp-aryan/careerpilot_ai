package ai.careerpilot.mcp.tool.memory;

import ai.careerpilot.domain.CareerDecisionMemory;
import ai.careerpilot.mcp.McpAuthenticationMode;
import ai.careerpilot.mcp.McpCapability;
import ai.careerpilot.mcp.McpExecutionContext;
import ai.careerpilot.mcp.McpMetrics;
import ai.careerpilot.mcp.McpRegistry;
import ai.careerpilot.mcp.McpServerDefinition;
import ai.careerpilot.mcp.McpToolDefinition;
import ai.careerpilot.mcp.tool.McpToolHandlerRegistry;
import ai.careerpilot.memory.CareerMemoryService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 10.2 — Memory MCP server. Purpose per the phase spec: long-term user memory (career
 * goals, learning progress, interview history, AI preferences, resume history, career
 * decisions). This is a thin wrapper around the existing Phase 7.15.1 {@link
 * CareerMemoryService} — no new memory store, no new table. {@link CareerMemoryService} already
 * degrades gracefully on its own ({@code career.memory.enabled=false} → every read method
 * returns an empty list/summary rather than throwing), so this server's handlers don't need
 * their own fallback logic for that case. Gated by BOTH {@code mcp.enabled} and {@code
 * mcp.memory.enabled} (both default {@code false}) — independent of, but practically useless
 * without, {@code career.memory.enabled} also being on.
 */
@Configuration
@ConditionalOnProperty(prefix = "mcp", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "mcp.memory", name = "enabled", havingValue = "true")
public class MemoryMcpServerConfig {

    private static final String SERVER = "memory";

    @Bean
    public McpServerDefinition memoryMcpServer(McpRegistry registry,
                                                McpToolHandlerRegistry handlers,
                                                McpMetrics metrics,
                                                CareerMemoryService memoryService) {
        McpServerDefinition server = new McpServerDefinition(
                SERVER, "1.0.0", Set.of(McpCapability.MEMORY), true, 1, McpAuthenticationMode.NONE);
        registry.registerServer(server);
        metrics.recordServerRegistered(SERVER);

        registerTool(registry, metrics, handlers,
                "get_career_memory_summary",
                "Returns a header-stat summary of the calling user's Career Decision Memory (counts by verification state, average confidence).",
                Map.of("type", "object", "properties", Map.of()),
                (args, context) -> getSummary(context, memoryService));

        registerTool(registry, metrics, handlers,
                "get_relevant_memories",
                "Returns the calling user's most relevant career decision memories, optionally filtered by category.",
                Map.of("type", "object", "properties", Map.of(
                        "category", Map.of("type", "string", "description", "optional category filter"),
                        "limit", Map.of("type", "integer", "description", "max results, default 5"))),
                (args, context) -> getRelevantMemories(context, memoryService, args));

        return server;
    }

    private void registerTool(McpRegistry registry, McpMetrics metrics, McpToolHandlerRegistry handlers,
                               String name, String description, Map<String, Object> inputSchema,
                               ai.careerpilot.mcp.tool.McpToolHandler handler) {
        McpToolDefinition tool = new McpToolDefinition(
                name, description, inputSchema, Map.of("type", "object"), McpCapability.MEMORY, SERVER);
        registry.registerTool(tool);
        metrics.recordToolRegistered(name);
        handlers.register(name, handler);
    }

    private Object getSummary(McpExecutionContext context, CareerMemoryService memoryService) {
        UUID userId = context.userId();
        if (userId == null) {
            return Map.of("available", false, "reason", "no authenticated user in context");
        }
        CareerMemoryService.MemorySummary summary = memoryService.summaryFor(userId);
        return Map.of(
                "available", true,
                "totalMemories", summary.totalMemories(),
                "verifiedCount", summary.verifiedCount(),
                "needsReviewCount", summary.needsReviewCount(),
                "averageConfidence", summary.averageConfidence());
    }

    private Object getRelevantMemories(McpExecutionContext context, CareerMemoryService memoryService, Map<String, Object> args) {
        UUID userId = context.userId();
        if (userId == null) {
            return Map.of("available", false, "reason", "no authenticated user in context");
        }
        int limit = args.get("limit") instanceof Number n ? n.intValue() : 5;
        Object categoryArg = args.get("category");
        List<CareerDecisionMemory> memories = (categoryArg instanceof String category && !category.isBlank())
                ? memoryService.relevantFor(userId, category, Math.max(1, limit))
                : memoryService.relevantFor(userId, Math.max(1, limit));

        List<Map<String, Object>> facts = memories.stream()
                .map(m -> Map.<String, Object>of(
                        "decisionType", m.getDecisionType() == null ? "" : m.getDecisionType(),
                        "category", m.getCategory() == null ? "" : m.getCategory(),
                        "value", m.getValue() == null ? "" : m.getValue(),
                        "confidence", m.getConfidence() == null ? "" : m.getConfidence().toString()))
                .toList();
        return Map.of("available", true, "count", facts.size(), "memories", facts);
    }
}
