package ai.careerpilot.mcp.tool.postgres;

import ai.careerpilot.domain.Application;
import ai.careerpilot.domain.CareerStrategy;
import ai.careerpilot.domain.JobRecommendation;
import ai.careerpilot.mcp.McpAuthenticationMode;
import ai.careerpilot.mcp.McpCapability;
import ai.careerpilot.mcp.McpExecutionContext;
import ai.careerpilot.mcp.McpMetrics;
import ai.careerpilot.mcp.McpRegistry;
import ai.careerpilot.mcp.McpServerDefinition;
import ai.careerpilot.mcp.McpToolDefinition;
import ai.careerpilot.mcp.tool.McpToolHandlerRegistry;
import ai.careerpilot.repo.ApplicationRepository;
import ai.careerpilot.repo.CareerStrategyRepository;
import ai.careerpilot.repo.JobRecommendationRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Phase 10.2 — PostgreSQL MCP server. Purpose per the phase spec: let the AI retrieve factual
 * data (job recommendations, application status, career strategy) from Postgres instead of
 * relying on a generated response. This does NOT execute raw SQL and does NOT add any new
 * query — every tool here is a thin read-only wrapper around an existing {@code JpaRepository}
 * finder method that already existed before this phase. Gated by BOTH {@code mcp.enabled} and
 * {@code mcp.postgres.enabled} (both default {@code false}).
 */
@Configuration
@ConditionalOnProperty(prefix = "mcp", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "mcp.postgres", name = "enabled", havingValue = "true")
public class PostgresMcpServerConfig {

    private static final String SERVER = "postgres";

    @Bean
    public McpServerDefinition postgresMcpServer(McpRegistry registry,
                                                   McpToolHandlerRegistry handlers,
                                                   McpMetrics metrics,
                                                   JobRecommendationRepository jobRecommendations,
                                                   ApplicationRepository applications,
                                                   CareerStrategyRepository careerStrategies) {
        McpServerDefinition server = new McpServerDefinition(
                SERVER, "1.0.0", Set.of(McpCapability.DATABASE), true, 1, McpAuthenticationMode.NONE);
        registry.registerServer(server);
        metrics.recordServerRegistered(SERVER);

        registerTool(registry, metrics, handlers,
                "get_job_recommendations",
                "Returns the calling user's top job recommendations, highest match score first.",
                Map.of("type", "object", "properties", Map.of(
                        "limit", Map.of("type", "integer", "description", "max results, default 5"))),
                (args, context) -> getJobRecommendations(context, jobRecommendations, args));

        registerTool(registry, metrics, handlers,
                "get_application_summary",
                "Returns a count of the calling user's applications grouped by status.",
                Map.of("type", "object", "properties", Map.of()),
                (args, context) -> getApplicationSummary(context, applications));

        registerTool(registry, metrics, handlers,
                "get_career_strategy_summary",
                "Returns the calling user's latest computed career strategy probabilities, if any.",
                Map.of("type", "object", "properties", Map.of()),
                (args, context) -> getCareerStrategySummary(context, careerStrategies));

        return server;
    }

    private void registerTool(McpRegistry registry, McpMetrics metrics, McpToolHandlerRegistry handlers,
                               String name, String description, Map<String, Object> inputSchema,
                               ai.careerpilot.mcp.tool.McpToolHandler handler) {
        McpToolDefinition tool = new McpToolDefinition(
                name, description, inputSchema, Map.of("type", "object"), McpCapability.DATABASE, SERVER);
        registry.registerTool(tool);
        metrics.recordToolRegistered(name);
        handlers.register(name, handler);
    }

    private Object getJobRecommendations(McpExecutionContext context, JobRecommendationRepository repo, Map<String, Object> args) {
        UUID userId = context.userId();
        if (userId == null) {
            return Map.of("available", false, "reason", "no authenticated user in context");
        }
        int limit = args.get("limit") instanceof Number n ? n.intValue() : 5;
        List<JobRecommendation> recs = repo.findByUserIdOrderByMatchScoreDesc(userId);
        List<Map<String, Object>> facts = recs.stream()
                .limit(Math.max(1, limit))
                .map(r -> Map.<String, Object>of(
                        "jobId", r.getJobId(),
                        "matchScore", r.getMatchScore(),
                        "category", r.getCategory() == null ? "" : r.getCategory(),
                        "priority", r.getPriority() == null ? "" : r.getPriority(),
                        "confidenceLevel", r.getConfidenceLevel() == null ? "" : r.getConfidenceLevel()))
                .toList();
        return Map.of("available", true, "count", facts.size(), "recommendations", facts);
    }

    private Object getApplicationSummary(McpExecutionContext context, ApplicationRepository repo) {
        UUID userId = context.userId();
        if (userId == null) {
            return Map.of("available", false, "reason", "no authenticated user in context");
        }
        List<Application> apps = repo.findByUserIdOrderByCreatedAtDesc(userId);
        Map<String, Long> byStatus = apps.stream()
                .collect(Collectors.groupingBy(
                        a -> a.getStatus() == null ? "UNKNOWN" : a.getStatus(), Collectors.counting()));
        return Map.of("available", true, "total", apps.size(), "byStatus", byStatus);
    }

    private Object getCareerStrategySummary(McpExecutionContext context, CareerStrategyRepository repo) {
        UUID userId = context.userId();
        if (userId == null) {
            return Map.of("available", false, "reason", "no authenticated user in context");
        }
        Optional<CareerStrategy> strategy = repo.findByUserId(userId);
        if (strategy.isEmpty()) {
            return Map.of("available", false, "reason", "no career strategy computed yet for this user");
        }
        CareerStrategy s = strategy.get();
        return Map.of(
                "available", true,
                "careerSuccessProbability", s.getCareerSuccessProbability() == null ? "" : s.getCareerSuccessProbability().toString(),
                "interviewProbability", s.getInterviewProbability() == null ? "" : s.getInterviewProbability().toString(),
                "offerProbability", s.getOfferProbability() == null ? "" : s.getOfferProbability().toString(),
                "recommendedTrajectory", s.getRecommendedTrajectory() == null ? "" : s.getRecommendedTrajectory());
    }
}
