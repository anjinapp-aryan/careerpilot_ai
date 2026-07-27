package ai.careerpilot.mcp.tool.postgres;

import ai.careerpilot.domain.Application;
import ai.careerpilot.domain.CareerStrategy;
import ai.careerpilot.domain.JobRecommendation;
import ai.careerpilot.mcp.InMemoryMcpMetrics;
import ai.careerpilot.mcp.InMemoryMcpRegistry;
import ai.careerpilot.mcp.McpExecutionContext;
import ai.careerpilot.mcp.McpServerDefinition;
import ai.careerpilot.mcp.tool.McpToolHandler;
import ai.careerpilot.mcp.tool.McpToolHandlerRegistry;
import ai.careerpilot.repo.ApplicationRepository;
import ai.careerpilot.repo.CareerStrategyRepository;
import ai.careerpilot.repo.JobRecommendationRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link PostgresMcpServerConfig} — verifies its three tools register correctly and each
 * handler returns facts sourced from the existing (mocked) repositories, degrading gracefully
 * when data is absent.
 */
class PostgresMcpServerConfigTest {

    private final InMemoryMcpRegistry registry = new InMemoryMcpRegistry();
    private final McpToolHandlerRegistry handlers = new McpToolHandlerRegistry();
    private final InMemoryMcpMetrics metrics = new InMemoryMcpMetrics();
    private final JobRecommendationRepository jobRecommendations = mock(JobRecommendationRepository.class);
    private final ApplicationRepository applications = mock(ApplicationRepository.class);
    private final CareerStrategyRepository careerStrategies = mock(CareerStrategyRepository.class);
    private final PostgresMcpServerConfig config = new PostgresMcpServerConfig();

    private McpExecutionContext contextFor(UUID userId) {
        return new McpExecutionContext(userId, null, null, "trace", Duration.ofSeconds(5), Map.of());
    }

    @Test
    void registersServerAndAllThreeTools() {
        McpServerDefinition server = config.postgresMcpServer(registry, handlers, metrics, jobRecommendations, applications, careerStrategies);

        assertThat(server.name()).isEqualTo("postgres");
        assertThat(registry.findTool("get_job_recommendations")).isPresent();
        assertThat(registry.findTool("get_application_summary")).isPresent();
        assertThat(registry.findTool("get_career_strategy_summary")).isPresent();
        assertThat(metrics.registeredToolCount()).isEqualTo(3);
    }

    @Test
    void jobRecommendationsHandlerReturnsTopMatchesRespectingLimit() {
        UUID userId = UUID.randomUUID();
        JobRecommendation r1 = JobRecommendation.builder().userId(userId).jobId(UUID.randomUUID()).matchScore(90).category("HIGH_PRIORITY").priority("HIGH").confidenceLevel("HIGH").build();
        JobRecommendation r2 = JobRecommendation.builder().userId(userId).jobId(UUID.randomUUID()).matchScore(70).category("RECOMMENDED").priority("MEDIUM").confidenceLevel("MEDIUM").build();
        when(jobRecommendations.findByUserIdOrderByMatchScoreDesc(userId)).thenReturn(List.of(r1, r2));

        config.postgresMcpServer(registry, handlers, metrics, jobRecommendations, applications, careerStrategies);
        McpToolHandler handler = handlers.find("get_job_recommendations").orElseThrow();

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) handler.handle(Map.of("limit", 1), contextFor(userId));

        assertThat(result.get("available")).isEqualTo(true);
        assertThat(result.get("count")).isEqualTo(1);
    }

    @Test
    void applicationSummaryHandlerGroupsByStatus() {
        UUID userId = UUID.randomUUID();
        Application a1 = Application.builder().userId(userId).orgId(UUID.randomUUID()).jobId(UUID.randomUUID()).status("APPLIED").build();
        Application a2 = Application.builder().userId(userId).orgId(UUID.randomUUID()).jobId(UUID.randomUUID()).status("APPLIED").build();
        Application a3 = Application.builder().userId(userId).orgId(UUID.randomUUID()).jobId(UUID.randomUUID()).status("INTERVIEWING").build();
        when(applications.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(a1, a2, a3));

        config.postgresMcpServer(registry, handlers, metrics, jobRecommendations, applications, careerStrategies);
        McpToolHandler handler = handlers.find("get_application_summary").orElseThrow();

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) handler.handle(Map.of(), contextFor(userId));

        assertThat(result.get("total")).isEqualTo(3);
        @SuppressWarnings("unchecked")
        Map<String, Long> byStatus = (Map<String, Long>) result.get("byStatus");
        assertThat(byStatus.get("APPLIED")).isEqualTo(2L);
        assertThat(byStatus.get("INTERVIEWING")).isEqualTo(1L);
    }

    @Test
    void careerStrategySummaryHandlerDegradesGracefullyWhenNoneComputed() {
        UUID userId = UUID.randomUUID();
        when(careerStrategies.findByUserId(userId)).thenReturn(Optional.empty());

        config.postgresMcpServer(registry, handlers, metrics, jobRecommendations, applications, careerStrategies);
        McpToolHandler handler = handlers.find("get_career_strategy_summary").orElseThrow();

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) handler.handle(Map.of(), contextFor(userId));

        assertThat(result.get("available")).isEqualTo(false);
    }

    @Test
    void careerStrategySummaryHandlerReturnsProbabilitiesWhenPresent() {
        UUID userId = UUID.randomUUID();
        CareerStrategy strategy = CareerStrategy.builder()
                .userId(userId)
                .careerSuccessProbability(new BigDecimal("0.75"))
                .build();
        when(careerStrategies.findByUserId(userId)).thenReturn(Optional.of(strategy));

        config.postgresMcpServer(registry, handlers, metrics, jobRecommendations, applications, careerStrategies);
        McpToolHandler handler = handlers.find("get_career_strategy_summary").orElseThrow();

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) handler.handle(Map.of(), contextFor(userId));

        assertThat(result.get("available")).isEqualTo(true);
        assertThat(result.get("careerSuccessProbability")).isEqualTo("0.75");
    }
}
