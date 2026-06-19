package ai.careerpilot.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Strongly-typed configuration for the AI Gateway. All values come from
 * environment variables via application.yml — nothing is hardcoded.
 *
 * <pre>
 * ai.gateway.primary            = gemini
 * ai.gateway.order              = gemini,deepseek,qwen   (failover order)
 * ai.gateway.default-temperature= 0.4
 * ai.gateway.providers.&lt;key&gt;.{display-name,api-key,model,base-url,timeout-ms}
 * </pre>
 */
@ConfigurationProperties(prefix = "ai.gateway")
public class AiGatewayProperties {

    /** The preferred provider key (reported by /api/ai/health). */
    private String primary = "gemini";

    /** Failover order of provider keys, highest priority first. */
    private List<String> order = new ArrayList<>(List.of("gemini", "deepseek", "qwen"));

    /** Default sampling temperature for chat/feature calls. */
    private double defaultTemperature = 0.4;

    /** Per-provider settings keyed by provider name (gemini | deepseek | qwen | …). */
    private Map<String, Provider> providers = new LinkedHashMap<>();

    public String getPrimary() { return primary; }
    public void setPrimary(String primary) { this.primary = primary; }

    public List<String> getOrder() { return order; }
    public void setOrder(List<String> order) { this.order = order; }

    public double getDefaultTemperature() { return defaultTemperature; }
    public void setDefaultTemperature(double defaultTemperature) { this.defaultTemperature = defaultTemperature; }

    public Map<String, Provider> getProviders() { return providers; }
    public void setProviders(Map<String, Provider> providers) { this.providers = providers; }

    public Provider provider(String key) {
        return providers.getOrDefault(key, new Provider());
    }

    /** Settings for a single LLM provider. */
    public static class Provider {
        private String displayName;
        private String apiKey;
        private String model;
        private String baseUrl;
        private long timeoutMs = 20_000;

        public String getDisplayName() { return displayName; }
        public void setDisplayName(String displayName) { this.displayName = displayName; }

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }

        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

        public long getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(long timeoutMs) { this.timeoutMs = timeoutMs; }
    }
}
