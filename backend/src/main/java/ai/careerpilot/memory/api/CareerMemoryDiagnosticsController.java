package ai.careerpilot.memory.api;

import ai.careerpilot.memory.CareerMemoryMetrics;
import ai.careerpilot.memory.config.CareerMemoryExecutorConfig;
import ai.careerpilot.memory.conversation.ConversationIntelligenceMetrics;
import ai.careerpilot.repo.CareerDecisionMemoryRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 7.15.1 — same no-auth, counts-only convention as {@code DiagnosticsController}/
 * {@code PipelineDiagnosticsController}: aggregate numbers only, never a per-user memory dump
 * (that would leak PII through an unauthenticated endpoint).
 */
@RestController
@RequestMapping("/api/diagnostics")
public class CareerMemoryDiagnosticsController {

    private final CareerDecisionMemoryRepository memories;
    private final CareerMemoryMetrics metrics;
    private final ConversationIntelligenceMetrics conversationMetrics;
    private final ThreadPoolTaskExecutor executor;

    @Value("${career.memory.enabled:false}") private boolean memoryEnabled;
    @Value("${copilot.career-memory-context.enabled:false}") private boolean copilotContextEnabled;
    @Value("${career.memory.conversation.enabled:false}") private boolean conversationEnabled;

    public CareerMemoryDiagnosticsController(
            CareerDecisionMemoryRepository memories, CareerMemoryMetrics metrics,
            ConversationIntelligenceMetrics conversationMetrics,
            @Qualifier(CareerMemoryExecutorConfig.CAREER_MEMORY_EXECUTOR) ThreadPoolTaskExecutor executor) {
        this.memories = memories;
        this.metrics = metrics;
        this.conversationMetrics = conversationMetrics;
        this.executor = executor;
    }

    @GetMapping("/career-memory")
    public Map<String, Object> diagnostics() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", memoryEnabled);
        out.put("copilotContextEnabled", copilotContextEnabled);
        out.put("totalMemories", memoryEnabled ? memories.count() : 0);
        out.put("executorActiveCount", executor.getActiveCount());
        out.put("executorQueueSize", executor.getThreadPoolExecutor().getQueue().size());
        out.put("health", !memoryEnabled ? "NOT_CONFIGURED"
                : executor.getThreadPoolExecutor().getQueue().size() >= executor.getQueueCapacity() ? "DEGRADED" : "UP");

        if (memoryEnabled) {
            out.putAll(metrics.snapshot());
            out.put("byCategory", asMap(memories.countByCategory()));
            out.put("bySource", asMap(memories.countBySource()));
            out.put("averageConfidence", memories.averageConfidence());
            Instant now = Instant.now();
            out.put("freshness", Map.of(
                    "createdLast7d", memories.countCreatedAfter(now.minus(7, ChronoUnit.DAYS)),
                    "createdLast30d", memories.countCreatedAfter(now.minus(30, ChronoUnit.DAYS)),
                    "createdLast90d", memories.countCreatedAfter(now.minus(90, ChronoUnit.DAYS))));
            out.put("topRemembered", memories.topRemembered(PageRequest.of(0, 10)).stream()
                    .map(row -> Map.of("decisionType", row[0], "value", row[1], "uses", row[2]))
                    .toList());
        }

        Map<String, Object> conversation = new LinkedHashMap<>();
        conversation.put("enabled", conversationEnabled);
        if (conversationEnabled) {
            conversation.putAll(conversationMetrics.snapshot());
        }
        out.put("conversationIntelligence", conversation);
        return out;
    }

    private static Map<String, Long> asMap(List<Object[]> rows) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (Object[] row : rows) {
            out.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        }
        return out;
    }
}
