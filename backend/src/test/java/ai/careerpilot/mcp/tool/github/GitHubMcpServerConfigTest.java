package ai.careerpilot.mcp.tool.github;

import ai.careerpilot.mcp.InMemoryMcpMetrics;
import ai.careerpilot.mcp.InMemoryMcpRegistry;
import ai.careerpilot.mcp.McpServerDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GitHubMcpServerConfig} — verifies server/tool registration, and exercises {@link
 * GitHubMcpServerConfig#summarize} directly (no real GitHub call) against fixture JSON matching
 * GitHub's repo-listing response shape.
 */
class GitHubMcpServerConfigTest {

    private final InMemoryMcpRegistry registry = new InMemoryMcpRegistry();
    private final ai.careerpilot.mcp.tool.McpToolHandlerRegistry handlers = new ai.careerpilot.mcp.tool.McpToolHandlerRegistry();
    private final InMemoryMcpMetrics metrics = new InMemoryMcpMetrics();
    private final GitHubMcpServerConfig config = new GitHubMcpServerConfig();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void registersServerAndTool() {
        McpServerDefinition server = config.githubMcpServer(registry, handlers, metrics, "https://api.github.com", "test-agent");

        assertThat(server.name()).isEqualTo("github");
        assertThat(registry.findTool("analyze_github_profile")).isPresent();
        assertThat(handlers.find("analyze_github_profile")).isPresent();
    }

    @Test
    void summarizeComputesLanguageBreakdownAndMostStarredRepo() throws Exception {
        JsonNode repo1 = mapper.readTree("{\"name\":\"api\",\"language\":\"Java\",\"stargazers_count\":10}");
        JsonNode repo2 = mapper.readTree("{\"name\":\"cli\",\"language\":\"Go\",\"stargazers_count\":25}");
        JsonNode repo3 = mapper.readTree("{\"name\":\"lib\",\"language\":\"Java\",\"stargazers_count\":2}");

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) GitHubMcpServerConfig.summarize("octocat", List.of(repo1, repo2, repo3));

        assertThat(result.get("repoCount")).isEqualTo(3);
        assertThat(result.get("mostStarredRepo")).isEqualTo("cli");
        assertThat(result.get("mostStarredRepoStars")).isEqualTo(25);
        @SuppressWarnings("unchecked")
        Map<String, Integer> languages = (Map<String, Integer>) result.get("languages");
        assertThat(languages.get("Java")).isEqualTo(2);
        assertThat(languages.get("Go")).isEqualTo(1);
    }

    @Test
    void summarizeDegradesGracefullyForUserWithNoPublicRepos() {
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) GitHubMcpServerConfig.summarize("nobody", List.of());

        assertThat(result.get("repoCount")).isEqualTo(0);
    }
}
