package ai.careerpilot.mcp.tool.github;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase 10.2 — keyless client for GitHub's public REST API, styled after {@code
 * ai.careerpilot.jobdiscovery.provider.AbstractWebJobProvider} (same {@code WebClient}
 * construction shape, same "let exceptions propagate to the caller" convention — see that
 * class's javadoc: the caller, {@code GitHubMcpServerConfig}'s handler via {@code
 * DefaultMcpExecutor}, is what converts a thrown exception into a graceful {@code
 * McpToolResult.failed(...)}, not this client). Unauthenticated GitHub API calls are subject to
 * GitHub's public rate limit (60 requests/hour per source IP) — acceptable for an
 * off-by-default, manually-invoked MCP tool, not for high-volume production traffic.
 *
 * <p>Deliberately NOT {@code @Component} — like every provider touched by Phase 9.3's
 * {@code ProviderRegistryConfig} pattern, this is constructed explicitly by {@link
 * GitHubMcpServerConfig}'s {@code @Bean} method only when {@code mcp.enabled} AND {@code
 * mcp.github.enabled} are both {@code true}, so it never exists in the application context
 * otherwise.
 */
public class GitHubApiClient {

    private final WebClient client;

    public GitHubApiClient(String baseUrl, String userAgent) {
        this.client = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("User-Agent", userAgent)
                .defaultHeader("Accept", "application/vnd.github+json")
                .codecs(c -> c.defaultCodecs().maxInMemorySize(4 * 1024 * 1024))
                .build();
    }

    /** Public repositories for a GitHub username, newest-updated first. Throws on failure — see class javadoc. */
    public List<JsonNode> listPublicRepos(String username) {
        JsonNode response = client.get()
                .uri(uri -> uri.path("/users/{username}/repos")
                        .queryParam("per_page", 100)
                        .queryParam("sort", "updated")
                        .build(username))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(15))
                .block();
        List<JsonNode> repos = new ArrayList<>();
        if (response != null && response.isArray()) {
            response.forEach(repos::add);
        }
        return repos;
    }
}
