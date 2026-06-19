package ai.careerpilot.ai.provider;

import ai.careerpilot.ai.AbstractLlmProvider;
import ai.careerpilot.ai.AiGatewayProperties;
import ai.careerpilot.ai.ChatMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Priority-1 provider: Google Gemini via its native v1beta REST API.
 * Implements {@code chat} ({@code :generateContent}) and {@code streamChat}
 * ({@code :streamGenerateContent?alt=sse}). Feature helpers come from
 * {@link AbstractLlmProvider}.
 */
@Component
public class GeminiProvider extends AbstractLlmProvider {

    private static final Logger log = LoggerFactory.getLogger(GeminiProvider.class);
    public static final String KEY = "gemini";

    private final AiGatewayProperties.Provider cfg;
    private final WebClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public GeminiProvider(AiGatewayProperties props) {
        this.cfg = props.provider(KEY);
        this.client = WebClient.builder()
                .baseUrl(cfg.getBaseUrl())
                .codecs(c -> c.defaultCodecs().maxInMemorySize(4 * 1024 * 1024))
                .build();
    }

    @Override public String name() { return KEY; }

    @Override public String displayName() {
        return cfg.getDisplayName() == null ? "Gemini" : cfg.getDisplayName();
    }

    @Override public boolean isConfigured() {
        return cfg.getApiKey() != null && !cfg.getApiKey().isBlank();
    }

    @Override public Duration timeout() { return Duration.ofMillis(cfg.getTimeoutMs()); }

    @Override
    public String chat(List<ChatMessage> messages, String system, double temperature) {
        Map<String, Object> body = buildBody(messages, system, temperature);
        JsonNode resp = client.post()
                .uri(uri -> uri.path("/models/{model}:generateContent")
                        .queryParam("key", cfg.getApiKey()).build(cfg.getModel()))
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(timeout())
                .block();
        return extractText(resp).trim();
    }

    @Override
    public Flux<String> streamChat(List<ChatMessage> messages, String system, double temperature) {
        Map<String, Object> body = buildBody(messages, system, temperature);
        return client.post()
                .uri(uri -> uri.path("/models/{model}:streamGenerateContent")
                        .queryParam("alt", "sse")
                        .queryParam("key", cfg.getApiKey()).build(cfg.getModel()))
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(String.class)
                .timeout(timeout())
                .flatMap(this::parseJsonResponse)
                .mapNotNull(this::extractText)
                .filter(text -> !text.isEmpty());
    }

    private Flux<JsonNode> parseJsonResponse(String jsonStr) {
        if (jsonStr == null || jsonStr.isEmpty() || jsonStr.startsWith(":")) {
            return Flux.empty();
        }
        try {
            JsonNode node = mapper.readTree(jsonStr);
            return Flux.just(node);
        } catch (Exception e) {
            log.warn("Failed to parse Gemini response chunk", e);
            return Flux.empty();
        }
    }

    // ---- helpers ----

    private Map<String, Object> buildBody(List<ChatMessage> messages, String system, double temperature) {
        List<Map<String, Object>> contents = messages.stream()
                .map(m -> Map.<String, Object>of(
                        "role", m.role(),                       // "user" | "model"
                        "parts", List.of(Map.of("text", m.content()))))
                .toList();
        Map<String, Object> generation = Map.of("temperature", temperature);
        if (system == null || system.isBlank()) {
            return Map.of("contents", contents, "generationConfig", generation);
        }
        return Map.of(
                "systemInstruction", Map.of("parts", List.of(Map.of("text", system))),
                "contents", contents,
                "generationConfig", generation);
    }

    /** Concatenate candidate text parts, preserving whitespace (for streaming chunks). */
    private String extractText(JsonNode resp) {
        if (resp == null) return "";
        JsonNode parts = resp.path("candidates").path(0).path("content").path("parts");
        StringBuilder sb = new StringBuilder();
        if (parts.isArray()) parts.forEach(p -> sb.append(p.path("text").asText("")));
        return sb.toString();
    }
}
