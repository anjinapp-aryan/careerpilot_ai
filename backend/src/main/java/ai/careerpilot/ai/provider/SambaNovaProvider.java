package ai.careerpilot.ai.provider;

import ai.careerpilot.ai.AiGatewayProperties;

/**
 * The single provider class for every SambaNova-hosted model (DeepSeek-V3.2,
 * DeepSeek-V3.1, gpt-oss-120b, Meta-Llama-3.3-70B-Instruct, gemma-4-31B-it,
 * MiniMax-M2.7). SambaNova's Cloud API is OpenAI-compatible, so — same as {@link
 * NvidiaProvider} — this is one class instantiated once per configured model (see
 * {@link SambaNovaProviderConfig}), each instance differing only in which {@code
 * ai.gateway.providers.<key>} block it was built from.
 *
 * <p>Transport, auth, streaming, retry, circuit breaker, health, and metrics are all
 * shared via {@link AbstractOpenAiChatProvider} and {@code AiGatewayService}'s
 * existing per-{@link #name()} registries — nothing about those changes.</p>
 */
public class SambaNovaProvider extends AbstractOpenAiChatProvider {

    private final String key;
    private final boolean enabledInSambaNovaCluster;

    public SambaNovaProvider(AiGatewayProperties props, String key) {
        super(props.provider(key));
        this.key = key;
        this.enabledInSambaNovaCluster = props.getSambaNova().isEnabled(key);
    }

    @Override public String name() { return key; }

    @Override public String displayName() {
        return cfg.getDisplayName() == null ? "SambaNova (" + key + ")" : cfg.getDisplayName();
    }

    /**
     * Adds the {@code ai.gateway.sambanova.models[].enabled} kill switch on top of the usual
     * api-key/model presence check — same mechanism as {@link NvidiaProvider}, so a SambaNova
     * model can be disabled without touching its (or any sibling model's) API key.
     */
    @Override public boolean isConfigured() {
        return enabledInSambaNovaCluster && super.isConfigured();
    }
}
