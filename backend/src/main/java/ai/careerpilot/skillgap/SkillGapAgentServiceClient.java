package ai.careerpilot.skillgap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.Map;

/**
 * Phase 10 — a thin HTTP client to the Python Skill Gap Intelligence endpoint ({@code POST
 * /skill-gap/runs}), structurally mirroring {@link ai.careerpilot.agent.AgentServiceClient} but
 * deliberately a separate class rather than a modification to it. Two reasons: (1) the frozen
 * Phase 9 "AI Execution Client" ({@code ai.careerpilot.runtime}) and {@code AgentServiceClient}
 * must not be touched per the architecture freeze — this workflow reuses what's safely reusable
 * (the Workflow Registry) without forcing a change into what's frozen; (2) {@code
 * AgentServiceClient.startRun} posts to the existing, live, shared {@code /runs} endpoint (the
 * main 8-node career graph) — routing this workflow through it would mean either misusing an
 * unrelated endpoint or modifying a live shared entry point, both worse than one small, isolated,
 * additive client. See {@code ai.careerpilot.skillgap}'s package-info for the full rationale.
 */
@Component
public class SkillGapAgentServiceClient {

    private static final Logger log = LoggerFactory.getLogger(SkillGapAgentServiceClient.class);

    private final WebClient client;
    private final Duration readTimeout;

    public SkillGapAgentServiceClient(
            @Value("${agent-service.base-url}") String baseUrl,
            @Value("${agent-service.read-timeout-ms}") long readTimeoutMs) {
        this.client = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
        this.readTimeout = Duration.ofMillis(readTimeoutMs);
    }

    public SkillGapAgentResponse startRun(Map<String, Object> payload) {
        try {
            log.info("skill_gap_agent_request_begin: endpoint=/skill-gap/runs, execution_id={}",
                    payload.get("execution_id"));
            SkillGapAgentResponse resp = client.post().uri("/skill-gap/runs").bodyValue(payload)
                    .retrieve().bodyToMono(SkillGapAgentResponse.class).timeout(readTimeout).block();
            log.info("skill_gap_agent_response: execution_id={}, status={}",
                    resp != null ? resp.executionId() : "null", resp != null ? resp.status() : "null");
            return resp;
        } catch (WebClientResponseException e) {
            log.error("skill_gap_agent_http_error: status={}, body={}", e.getStatusCode(), e.getResponseBodyAsString(), e);
            throw new SkillGapAgentServiceException("Skill Gap agent-service HTTP error: " + e.getStatusCode(), e);
        } catch (Exception e) {
            log.error("skill_gap_agent_request_failed: error_type={}, error_msg={}",
                    e.getClass().getSimpleName(), e.getMessage(), e);
            throw new SkillGapAgentServiceException("Skill Gap agent-service unavailable", e);
        }
    }

    /** Runtime exception for Skill Gap agent-service failures. */
    public static class SkillGapAgentServiceException extends RuntimeException {
        public SkillGapAgentServiceException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
