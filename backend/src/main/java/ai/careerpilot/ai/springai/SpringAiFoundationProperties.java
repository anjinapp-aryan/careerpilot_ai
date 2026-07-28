package ai.careerpilot.ai.springai;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Strongly-typed configuration for the Phase 9.1 Spring AI foundation beans. Binds
 * {@code ai.springai.foundation.*} (see application.yml) — a namespace entirely
 * separate from {@code ai.gateway.*} ({@link ai.careerpilot.ai.AiGatewayProperties}).
 *
 * <p>As of Phase 10.3.1, also read by {@code ai.careerpilot.capability.CapabilityAwareChatService}
 * (via {@code ObjectProvider}, since {@code capability.engine.enabled} and {@code
 * ai.springai.foundation.enabled} are independent flags): a {@link
 * org.springframework.ai.chat.prompt.Prompt}'s own {@code ChatOptions} REPLACE — not merge with
 * — the {@code ChatModel} bean's default options, so real Spring AI tool calling must carry
 * {@link #getChatModel()} forward explicitly on every call or the request silently falls back to
 * Spring AI's own hardcoded default model name (caught live: sent {@code "gpt-5-mini"} to
 * NVIDIA, which 404'd since that model doesn't exist on that gateway).
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
