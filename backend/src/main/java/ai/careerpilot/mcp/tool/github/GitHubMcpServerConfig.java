package ai.careerpilot.mcp.tool.github;

import ai.careerpilot.mcp.McpAuthenticationMode;
import ai.careerpilot.mcp.McpCapability;
import ai.careerpilot.mcp.McpMetrics;
import ai.careerpilot.mcp.McpRegistry;
import ai.careerpilot.mcp.McpServerDefinition;
import ai.careerpilot.mcp.McpToolDefinition;
import ai.careerpilot.mcp.tool.McpToolHandlerRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phase 10.2 — GitHub MCP server. Purpose per the phase spec: repository/portfolio analysis,
 * language and architecture signal extraction, resume enrichment / skill validation. Keyless
 * (GitHub's public repo-listing endpoint needs no auth), so this server is always "configured"
 * once its flag is on — unlike Context7 below. Gated by BOTH {@code mcp.enabled} and {@code
 * mcp.github.enabled} (both default {@code false}).
 */
@Configuration
@ConditionalOnProperty(prefix = "mcp", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "mcp.github", name = "enabled", havingValue = "true")
public class GitHubMcpServerConfig {

    private static final String SERVER = "github";
    private static final String TOOL = "analyze_github_profile";

    @Bean
    public McpServerDefinition githubMcpServer(
            McpRegistry registry,
            McpToolHandlerRegistry handlers,
            McpMetrics metrics,
            @Value("${mcp.github.base-url:https://api.github.com}") String baseUrl,
            @Value("${mcp.github.user-agent:CareerPilotAI/1.0 (+https://careerpilot.ai)}") String userAgent) {
        McpServerDefinition server = new McpServerDefinition(
                SERVER, "1.0.0", Set.of(McpCapability.GITHUB), true, 1, McpAuthenticationMode.NONE);
        registry.registerServer(server);
        metrics.recordServerRegistered(SERVER);

        McpToolDefinition tool = new McpToolDefinition(
                TOOL,
                "Analyzes a GitHub username's public repositories: language breakdown, repo count, most-starred repo.",
                Map.of("type", "object", "properties", Map.of(
                        "username", Map.of("type", "string", "description", "GitHub username to analyze"))),
                Map.of("type", "object"),
                McpCapability.GITHUB,
                SERVER);
        registry.registerTool(tool);
        metrics.recordToolRegistered(TOOL);

        GitHubApiClient client = new GitHubApiClient(baseUrl, userAgent);
        handlers.register(TOOL, (args, context) -> handle(args, client));
        return server;
    }

    private Object handle(Map<String, Object> args, GitHubApiClient client) {
        Object usernameArg = args.get("username");
        if (!(usernameArg instanceof String username) || username.isBlank()) {
            return Map.of("available", false, "reason", "missing required argument 'username'");
        }

        List<JsonNode> repos = client.listPublicRepos(username);
        return summarize(username, repos);
    }

    /**
     * Pure computation, extracted from {@link #handle} so it's testable without a real GitHub
     * call — see {@code GitHubMcpServerConfigTest}.
     */
    static Object summarize(String username, List<JsonNode> repos) {
        if (repos.isEmpty()) {
            return Map.of("available", true, "username", username, "repoCount", 0, "languages", Map.of());
        }

        Map<String, Integer> languageCounts = new LinkedHashMap<>();
        String topRepoName = "";
        int topStars = -1;
        for (JsonNode repo : repos) {
            String language = repo.path("language").isNull() ? null : repo.path("language").asText(null);
            if (language != null && !language.isBlank()) {
                languageCounts.merge(language, 1, Integer::sum);
            }
            int stars = repo.path("stargazers_count").asInt(0);
            if (stars > topStars) {
                topStars = stars;
                topRepoName = repo.path("name").asText("");
            }
        }

        return Map.of(
                "available", true,
                "username", username,
                "repoCount", repos.size(),
                "languages", languageCounts,
                "mostStarredRepo", topRepoName,
                "mostStarredRepoStars", Math.max(topStars, 0));
    }
}
