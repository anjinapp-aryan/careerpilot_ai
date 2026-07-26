package ai.careerpilot.ai.provider;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A single OpenRouter catalog entry, as returned by {@code GET /v1/models} — populated
 * entirely from that live response, never hand-written. Latency/health/priority are
 * deliberately NOT fields here: latency comes from {@code AiMetrics.avgLatencyMs}, health
 * from {@code ProviderHealthTracker}, and priority from {@code
 * ai.gateway.open-router.capabilities.<name>.preferred}'s list order — duplicating any of
 * those into this record would create a second, driftable source of truth.
 */
public record OpenRouterModelMetadata(
        String id,
        String provider,
        Long contextWindow,
        Double inputCostPerToken,
        Double outputCostPerToken,
        boolean supportsToolCalling,
        boolean supportsStructuredOutput,
        boolean supportsVision
) {
    /** Every OpenRouter chat model is called through the same streaming-capable transport. */
    public boolean supportsStreaming() {
        return true;
    }

    static OpenRouterModelMetadata fromCatalogEntry(JsonNode entry) {
        String id = entry.path("id").asText(null);
        String provider = id == null ? null : (id.contains("/") ? id.substring(0, id.indexOf('/')) : id);
        Long contextWindow = entry.hasNonNull("context_length") ? entry.path("context_length").asLong() : null;

        JsonNode pricing = entry.path("pricing");
        Double inputCost = parsePrice(pricing.path("prompt"));
        Double outputCost = parsePrice(pricing.path("completion"));

        JsonNode supported = entry.path("supported_parameters");
        boolean toolCalling = containsIgnoreCase(supported, "tools") || containsIgnoreCase(supported, "tool_choice");
        boolean structuredOutput = containsIgnoreCase(supported, "response_format")
                || containsIgnoreCase(supported, "structured_outputs");
        boolean vision = entry.path("architecture").path("modality").asText("").contains("image");

        return new OpenRouterModelMetadata(id, provider, contextWindow, inputCost, outputCost,
                toolCalling, structuredOutput, vision);
    }

    private static Double parsePrice(JsonNode priceNode) {
        if (priceNode.isMissingNode() || priceNode.isNull()) return null;
        try {
            return Double.parseDouble(priceNode.asText());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean containsIgnoreCase(JsonNode arrayNode, String value) {
        if (!arrayNode.isArray()) return false;
        for (JsonNode n : arrayNode) {
            if (value.equalsIgnoreCase(n.asText(""))) return true;
        }
        return false;
    }
}
