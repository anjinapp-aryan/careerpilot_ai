package ai.careerpilot.agent;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;

/** Thin HTTP client to the Python LangGraph agent service. */
@Component
public class AgentServiceClient {

    private final WebClient client;
    private final Duration readTimeout;

    public AgentServiceClient(
            @Value("${agent-service.base-url}") String baseUrl,
            @Value("${agent-service.read-timeout-ms}") long readTimeoutMs) {
        this.client = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
        this.readTimeout = Duration.ofMillis(readTimeoutMs);
    }

    public JsonNode startRun(Map<String, Object> payload) {
        return client.post().uri("/runs").bodyValue(payload)
                .retrieve().bodyToMono(JsonNode.class).timeout(readTimeout).block();
    }

    public JsonNode resumeRun(String threadId, String decision, String feedback) {
        return client.post().uri("/runs/resume")
                .bodyValue(Map.of("thread_id", threadId, "human_decision", decision, "human_feedback", feedback))
                .retrieve().bodyToMono(JsonNode.class).timeout(readTimeout).block();
    }

    public JsonNode getRun(String threadId) {
        return client.get().uri("/runs/{id}", threadId)
                .retrieve().bodyToMono(JsonNode.class).timeout(readTimeout).block();
    }
}
