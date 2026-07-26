package ai.careerpilot.ai.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Parses a hand-built OpenRouter {@code /v1/models} catalog entry — no network involved. */
class OpenRouterModelMetadataTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void parsesIdProviderContextWindowAndPricing() throws Exception {
        JsonNode entry = MAPPER.readTree("""
                {
                  "id": "deepseek/deepseek-v4-flash",
                  "context_length": 1048576,
                  "pricing": {"prompt": "0.00000014", "completion": "0.00000028"},
                  "supported_parameters": ["tools", "response_format"],
                  "architecture": {"modality": "text->text"}
                }
                """);

        OpenRouterModelMetadata meta = OpenRouterModelMetadata.fromCatalogEntry(entry);

        assertThat(meta.id()).isEqualTo("deepseek/deepseek-v4-flash");
        assertThat(meta.provider()).isEqualTo("deepseek");
        assertThat(meta.contextWindow()).isEqualTo(1048576L);
        assertThat(meta.inputCostPerToken()).isEqualTo(0.00000014);
        assertThat(meta.outputCostPerToken()).isEqualTo(0.00000028);
        assertThat(meta.supportsToolCalling()).isTrue();
        assertThat(meta.supportsStructuredOutput()).isTrue();
        assertThat(meta.supportsVision()).isFalse();
        assertThat(meta.supportsStreaming()).isTrue();
    }

    @Test
    void freeModelHasZeroPricing() throws Exception {
        JsonNode entry = MAPPER.readTree("""
                {
                  "id": "nvidia/nemotron-3-ultra-550b-a55b:free",
                  "context_length": 1000000,
                  "pricing": {"prompt": "0", "completion": "0"},
                  "supported_parameters": [],
                  "architecture": {"modality": "text->text"}
                }
                """);

        OpenRouterModelMetadata meta = OpenRouterModelMetadata.fromCatalogEntry(entry);

        assertThat(meta.inputCostPerToken()).isZero();
        assertThat(meta.outputCostPerToken()).isZero();
        assertThat(meta.supportsToolCalling()).isFalse();
    }

    @Test
    void modalityContainingImageMeansVisionSupport() throws Exception {
        JsonNode entry = MAPPER.readTree("""
                {"id": "some/vl-model", "architecture": {"modality": "text+image->text"}}
                """);

        OpenRouterModelMetadata meta = OpenRouterModelMetadata.fromCatalogEntry(entry);

        assertThat(meta.supportsVision()).isTrue();
    }

    @Test
    void missingPricingFieldsParseAsNull_notZero_neverFabricated() throws Exception {
        JsonNode entry = MAPPER.readTree("""
                {"id": "some/model"}
                """);

        OpenRouterModelMetadata meta = OpenRouterModelMetadata.fromCatalogEntry(entry);

        assertThat(meta.inputCostPerToken()).isNull();
        assertThat(meta.outputCostPerToken()).isNull();
        assertThat(meta.contextWindow()).isNull();
    }
}
