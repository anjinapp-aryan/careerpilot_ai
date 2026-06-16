package ai.careerpilot.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "ai.provider", havingValue = "gemini", matchIfMissing = true)
public class GeminiProvider implements AIProvider {

    private static final double PRICE_IN_PER_1M = 1.25;
    private static final double PRICE_OUT_PER_1M = 5.00;

    private final WebClient client;
    private final String model;
    private final String apiKey;
    private final ObjectMapper mapper = new ObjectMapper();

    public GeminiProvider(
            @Value("${ai.gemini.api-key}") String apiKey,
            @Value("${ai.model}") String model) {
        this.apiKey = apiKey;
        this.model = model;
        this.client = WebClient.builder()
                .baseUrl("https://generativelanguage.googleapis.com/v1beta")
                .build();
    }

    @Override
    public String generateResponse(String prompt, String system, double temperature) {
        Map<String, Object> body = buildBody(prompt, system, temperature, null);
        JsonNode resp = call(body);
        return extractText(resp);
    }

    @Override
    public Map<String, Object> generateStructuredResponse(String prompt, Map<String, Object> jsonSchema, String system) {
        Map<String, Object> genCfg = Map.of(
                "temperature", 0.2,
                "responseMimeType", "application/json",
                "responseSchema", jsonSchema);
        Map<String, Object> body = buildBody(prompt, system, 0.2, genCfg);
        JsonNode resp = call(body);
        try {
            return mapper.readValue(extractText(resp), Map.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Gemini JSON", e);
        }
    }

    @Override
    public Map<String, Object> generateJson(String prompt, String system) {
        return generateStructuredResponse(prompt, Map.of("type", "object"), system);
    }

    @Override
    public double estimateCost(int inputTokens, int outputTokens) {
        return (inputTokens / 1_000_000.0) * PRICE_IN_PER_1M + (outputTokens / 1_000_000.0) * PRICE_OUT_PER_1M;
    }

    private Map<String, Object> buildBody(String prompt, String system, double temperature, Map<String, Object> genCfg) {
        Map<String, Object> contents = Map.of(
                "role", "user",
                "parts", List.of(Map.of("text", prompt)));
        Map<String, Object> generation = genCfg != null ? genCfg : Map.of("temperature", temperature);
        if (system == null || system.isBlank()) {
            return Map.of("contents", List.of(contents), "generationConfig", generation);
        }
        return Map.of(
                "systemInstruction", Map.of("parts", List.of(Map.of("text", system))),
                "contents", List.of(contents),
                "generationConfig", generation);
    }

    private JsonNode call(Map<String, Object> body) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("GEMINI_API_KEY is not configured");
        }
        return client.post()
                .uri(uri -> uri.path("/models/{model}:generateContent").queryParam("key", apiKey).build(model))
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(Duration.ofSeconds(60))
                .block();
    }

    private String extractText(JsonNode resp) {
        if (resp == null) return "";
        JsonNode parts = resp.path("candidates").path(0).path("content").path("parts");
        StringBuilder sb = new StringBuilder();
        if (parts.isArray()) parts.forEach(p -> sb.append(p.path("text").asText("")));
        return sb.toString().trim();
    }
}
