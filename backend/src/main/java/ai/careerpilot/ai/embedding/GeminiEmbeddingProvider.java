package ai.careerpilot.ai.embedding;

import ai.careerpilot.ai.AiGatewayProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Gemini embeddings via the native v1beta REST API ({@code :embedContent}). Reuses the gemini API
 * key + base URL from {@link AiGatewayProperties} (no separate key) but a dedicated embedding model
 * (default {@code text-embedding-004}, 768-d). Retry-free and self-contained — {@link EmbeddingService}
 * isolates failures.
 */
@Component
public class GeminiEmbeddingProvider implements EmbeddingProvider {

    public static final String KEY = "gemini";

    private final AiGatewayProperties.Provider geminiCfg;
    private final WebClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    private final String model;
    private final int dimensions;

    public GeminiEmbeddingProvider(AiGatewayProperties props,
                                   @Value("${ai.embeddings.model:text-embedding-004}") String model,
                                   @Value("${ai.embeddings.dimensions:768}") int dimensions) {
        this.geminiCfg = props.provider(KEY);
        this.model = model;
        this.dimensions = dimensions;
        this.client = WebClient.builder()
                .baseUrl(geminiCfg.getBaseUrl())
                .codecs(c -> c.defaultCodecs().maxInMemorySize(4 * 1024 * 1024))
                .build();
    }

    @Override public String name() { return KEY; }

    @Override public boolean isConfigured() {
        return geminiCfg.getApiKey() != null && !geminiCfg.getApiKey().isBlank();
    }

    @Override public int dimensions() { return dimensions; }

    @Override
    public float[] embed(String text) {
        // Gemini requires the model id to be prefixed with "models/" inside the body.
        // outputDimensionality pins the vector to the DB column width (gemini-embedding-001 would
        // otherwise return 3072-d). Cosine ordering is scale-invariant, so no re-normalization needed.
        Map<String, Object> body = Map.of(
                "model", "models/" + model,
                "content", Map.of("parts", List.of(Map.of("text", text))),
                "outputDimensionality", dimensions);
        String raw = client.post()
                .uri(uri -> uri.path("/models/{model}:embedContent")
                        .queryParam("key", geminiCfg.getApiKey()).build(model))
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofMillis(geminiCfg.getTimeoutMs()))
                .block();
        return parseValues(raw);
    }

    private float[] parseValues(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException("Empty embedding response from Gemini");
        }
        try {
            JsonNode values = mapper.readTree(raw).path("embedding").path("values");
            if (!values.isArray() || values.isEmpty()) {
                throw new IllegalStateException("Embedding response missing values array");
            }
            float[] vec = new float[values.size()];
            for (int i = 0; i < values.size(); i++) {
                vec[i] = (float) values.get(i).asDouble();
            }
            return vec;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse Gemini embedding response", e);
        }
    }
}
