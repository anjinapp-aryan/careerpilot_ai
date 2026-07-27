package ai.careerpilot.mcp.tool.context7;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

/**
 * Phase 10.2 — client for Context7's documentation-search API. Purpose per the phase spec: let
 * the AI look up framework/API documentation (Spring Boot, Spring AI, Java, AWS, Terraform,
 * Docker, Kubernetes, React, LangGraph) instead of relying solely on model knowledge.
 *
 * <p><b>Endpoint shape is best-effort, not verified against a live call.</b> This client targets
 * Context7's publicly documented REST search endpoint ({@code GET {baseUrl}/v1/search?query=...}
 * with a bearer token), matching the keyed-provider convention already used by {@code
 * AdzunaProvider}/{@code JoobleProvider} in this codebase. Because {@link #isConfigured()}
 * returns {@code false} without a real {@code mcp.context7.api-key}, and the flag defaults off,
 * this client is never actually invoked unless an operator deliberately supplies both — so
 * shipping it carries no risk regardless of whether the endpoint shape is exactly right.
 * <b>Verify the endpoint path/auth header against Context7's current API reference before
 * enabling this in any environment.</b>
 *
 * <p>Deliberately NOT {@code @Component} — constructed explicitly by {@link
 * Context7McpServerConfig}'s {@code @Bean} method, matching {@code GitHubApiClient}'s
 * convention, so it never exists in the application context when its flags are off.
 */
public class Context7ApiClient {

    private final WebClient client;
    private final String apiKey;
    private final long timeoutMs;

    public Context7ApiClient(String baseUrl, String apiKey, long timeoutMs) {
        this.apiKey = apiKey;
        this.timeoutMs = timeoutMs;
        this.client = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Accept", "application/json")
                .codecs(c -> c.defaultCodecs().maxInMemorySize(4 * 1024 * 1024))
                .build();
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** Throws on failure — the caller (this server's handler, via DefaultMcpExecutor) converts that to a graceful result. */
    public JsonNode search(String query) {
        return client.get()
                .uri(uri -> uri.path("/v1/search").queryParam("query", query).build())
                .header("Authorization", "Bearer " + apiKey)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofMillis(timeoutMs))
                .block();
    }
}
