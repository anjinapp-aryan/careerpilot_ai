package ai.careerpilot.agent;

import ai.careerpilot.api.dto.AgentServiceDtos.AgentRunResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.Map;

/** Thin HTTP client to the Python LangGraph agent service. */
@Component
public class AgentServiceClient {

    private static final Logger log = LoggerFactory.getLogger(AgentServiceClient.class);

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

    public AgentRunResponse startRun(Map<String, Object> payload) {
        try {
            log.debug("Agent startRun request: user_id={}", payload.get("user_id"));
            log.info("agent_request_begin: endpoint=/runs");
            AgentRunResponse resp = client.post().uri("/runs").bodyValue(payload)
                    .retrieve().bodyToMono(AgentRunResponse.class).timeout(readTimeout).block();
            log.info("agent_response_deserialized: thread_id={}, status={}, state_keys={}",
                    resp != null ? resp.thread_id() : "null",
                    resp != null ? resp.status() : "null",
                    resp != null ? resp.state().keySet() : "null");
            if (resp != null) {
                Map<String, Object> state = resp.state();
                for (String key : state.keySet()) {
                    Object value = state.get(key);
                    log.debug("state_field: key={}, value_type={}", key,
                        value != null ? value.getClass().getSimpleName() : "null");
                }
            }
            return resp;
        } catch (WebClientResponseException e) {
            log.error("Agent startRun HTTP error: status={}, body={}",
                    e.getStatusCode(), e.getResponseBodyAsString(), e);
            throw new AgentServiceException("Agent service HTTP error: " + e.getStatusCode(), e);
        } catch (Exception e) {
            log.error("Agent startRun failed: error_type={}, error_msg={}",
                    e.getClass().getSimpleName(), e.getMessage(), e);
            throw new AgentServiceException("Agent service unavailable", e);
        }
    }

    public AgentRunResponse resumeRun(String threadId, String decision, String feedback) {
        try {
            log.debug("Agent resumeRun: thread_id={}, decision={}", threadId, decision);
            AgentRunResponse resp = client.post().uri("/runs/resume")
                    .bodyValue(Map.of("thread_id", threadId, "human_decision", decision, "human_feedback", feedback))
                    .retrieve().bodyToMono(AgentRunResponse.class).timeout(readTimeout).block();
            log.info("Agent resumeRun success: thread_id={}, status={}",
                    resp != null ? resp.thread_id() : "null",
                    resp != null ? resp.status() : "null");
            return resp;
        } catch (WebClientResponseException e) {
            log.error("Agent resumeRun HTTP error: status={}, body={}",
                    e.getStatusCode(), e.getResponseBodyAsString(), e);
            throw new AgentServiceException("Agent service HTTP error: " + e.getStatusCode(), e);
        } catch (Exception e) {
            log.error("Agent resumeRun failed", e);
            throw new AgentServiceException("Agent service unavailable", e);
        }
    }

    public AgentRunResponse getRun(String threadId) {
        try {
            log.debug("Agent getRun: thread_id={}", threadId);
            AgentRunResponse resp = client.get().uri("/runs/{id}", threadId)
                    .retrieve().bodyToMono(AgentRunResponse.class).timeout(readTimeout).block();
            log.debug("Agent getRun success: thread_id={}, status={}",
                    resp != null ? resp.thread_id() : "null",
                    resp != null ? resp.status() : "null");
            return resp;
        } catch (WebClientResponseException e) {
            log.error("Agent getRun HTTP error: status={}, body={}",
                    e.getStatusCode(), e.getResponseBodyAsString(), e);
            throw new AgentServiceException("Agent service HTTP error: " + e.getStatusCode(), e);
        } catch (Exception e) {
            log.error("Agent getRun failed", e);
            throw new AgentServiceException("Agent service unavailable", e);
        }
    }

    /** Runtime exception for agent service failures. */
    public static class AgentServiceException extends RuntimeException {
        public AgentServiceException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
