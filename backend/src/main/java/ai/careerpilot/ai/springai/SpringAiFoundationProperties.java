package ai.careerpilot.ai.springai;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Strongly-typed configuration for the Phase 9.1 Spring AI foundation beans. Binds
 * {@code ai.springai.foundation.*} (see application.yml) — a namespace entirely
 * separate from {@code ai.gateway.*} ({@link ai.careerpilot.ai.AiGatewayProperties}).
 * Nothing here is read outside {@link SpringAiConfig}.
 *
 * <pre>
 * ai.springai.foundation.enabled          = false   (dark by default)
 * ai.springai.foundation.base-url         = https://integrate.api.nvidia.com/v1
 * ai.springai.foundation.api-key          = (blank by default)
 * ai.springai.foundation.chat-model       = deepseek-ai/deepseek-v4-flash
 * ai.springai.foundation.embedding-model  = nvidia/nv-embedqa-e5-v5
 * ai.springai.foundation.timeout-ms       = 20000
 * </pre>
 */
@ConfigurationProperties(prefix = "ai.springai.foundation")
public class SpringAiFoundationProperties {

    /** Master switch. When false, {@link SpringAiConfig} creates no beans at all. */
    private boolean enabled = false;

    private String baseUrl = "https://integrate.api.nvidia.com/v1";
    private String apiKey = "";
    private String chatModel = "deepseek-ai/deepseek-v4-flash";
    private String embeddingModel = "nvidia/nv-embedqa-e5-v5";
    private long timeoutMs = 20000;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getChatModel() { return chatModel; }
    public void setChatModel(String chatModel) { this.chatModel = chatModel; }

    public String getEmbeddingModel() { return embeddingModel; }
    public void setEmbeddingModel(String embeddingModel) { this.embeddingModel = embeddingModel; }

    public long getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(long timeoutMs) { this.timeoutMs = timeoutMs; }
}
