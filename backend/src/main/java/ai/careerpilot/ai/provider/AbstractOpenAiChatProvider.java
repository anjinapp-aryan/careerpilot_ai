package ai.careerpilot.ai.provider;

import ai.careerpilot.ai.AbstractLlmProvider;
import ai.careerpilot.ai.AiGatewayProperties;
import ai.careerpilot.ai.ChatMessage;
import ai.careerpilot.ai.QuotaExceededException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Base for any OpenAI-compatible Chat Completions API ({@code /chat/completions}).
 * NVIDIA's hosted models (DeepSeek, Qwen, …) all speak this protocol, so a single
 * base supports every NVIDIA model — and any future OpenAI-compatible provider
 * (OpenRouter, Groq, local vLLM) is one more subclass.
 */
public abstract class AbstractOpenAiChatProvider extends AbstractLlmProvider {

    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE_TYPE =
            new ParameterizedTypeReference<>() {};

    protected final AiGatewayProperties.Provider cfg;
    private final WebClient client;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

    protected AbstractOpenAiChatProvider(AiGatewayProperties.Provider cfg) {
        this.cfg = cfg;
        this.client = WebClient.builder()
                .baseUrl(cfg.getBaseUrl() == null ? "" : cfg.getBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + (cfg.getApiKey() == null ? "" : cfg.getApiKey()))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(4 * 1024 * 1024))
                .build();
    }

    @Override public boolean isConfigured() {
        return cfg.getApiKey() != null && !cfg.getApiKey().isBlank()
                && cfg.getModel() != null && !cfg.getModel().isBlank();
    }

    @Override public Duration timeout() { return Duration.ofMillis(cfg.getTimeoutMs()); }

    @Override
    public String chat(List<ChatMessage> messages, String system, double temperature) {
        Map<String, Object> body = Map.of(
                "model", cfg.getModel(),
                "messages", toOpenAiMessages(messages, system),
                "temperature", temperature,
                "stream", false);
        JsonNode resp = client.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                // 429 → throw the typed quota exception so the gateway fails over
                // immediately instead of wasting its retry budget on a rate-limited
                // provider (Resilience4j ignores QuotaExceededException for retry).
                .onStatus(status -> status.value() == 429,
                        r -> Mono.error(new QuotaExceededException(displayName() + " 429 quota/rate limit", null)))
                .bodyToMono(JsonNode.class)
                .timeout(timeout())
                .block();
        if (resp == null) return "";
        return resp.path("choices").path(0).path("message").path("content").asText("").trim();
    }

    @Override
    public Flux<String> streamChat(List<ChatMessage> messages, String system, double temperature) {
        Map<String, Object> body = Map.of(
                "model", cfg.getModel(),
                "messages", toOpenAiMessages(messages, system),
                "temperature", temperature,
                "stream", true);
        return client.post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body)
                .retrieve()
                .onStatus(status -> status.value() == 429,
                        r -> Mono.error(new QuotaExceededException(displayName() + " 429 quota/rate limit", null)))
                .bodyToFlux(SSE_TYPE)
                .timeout(timeout())
                .mapNotNull(ServerSentEvent::data)
                .takeUntil("[DONE]"::equals)
                .filter(data -> !"[DONE]".equals(data))
                .map(this::extractDelta)
                .filter(text -> !text.isEmpty());
    }

    // ---- helpers ----

    /** OpenAI roles: system | user | assistant. Map our "model" → "assistant". */
    private List<Map<String, Object>> toOpenAiMessages(List<ChatMessage> messages, String system) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (system != null && !system.isBlank()) {
            out.add(Map.of("role", "system", "content", system));
        }
        for (ChatMessage m : messages) {
            String role = "model".equals(m.role()) ? "assistant" : "user";
            out.add(Map.of("role", role, "content", m.content()));
        }
        return out;
    }

    private String extractDelta(String data) {
        try {
            JsonNode node = mapper.readTree(data);
            return node.path("choices").path(0).path("delta").path("content").asText("");
        } catch (Exception e) {
            return "";
        }
    }
}
