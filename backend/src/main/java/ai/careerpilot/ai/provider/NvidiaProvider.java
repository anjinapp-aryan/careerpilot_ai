package ai.careerpilot.ai.provider;

import ai.careerpilot.ai.AiGatewayProperties;

/**
 * The single provider class for every NVIDIA NIM-hosted model (DeepSeek Flash,
 * DeepSeek Pro/reasoning, Kimi K2, GLM, …). All four speak the same OpenAI-compatible
 * endpoint — modeling each model as its own hand-written {@code LlmProvider} subclass
 * (the previous {@code NvidiaDeepSeekProvider}/{@code DeepSeekFlashProvider}/{@code
 * GlmProvider}) meant four files with identical transport code differing only in
 * which config block they read. This class replaces all three: it is instantiated
 * once per configured NVIDIA model (see {@link NvidiaProviderConfig}), each instance
 * differing ONLY in which {@code ai.gateway.providers.<key>} block it was built from
 * — including, if configured that way, its own dedicated API key (models do not have
 * to share one; each provider-key config in {@code ai.gateway.providers.<key>.api-key}
 * is independent).
 *
 * <p>Transport, auth, streaming, retry, circuit breaker, health, and metrics are
 * still 100% shared — via {@link AbstractOpenAiChatProvider} for transport, and via
 * {@code AiGatewayService}'s existing per-{@link #name()} registries for retry/CB/
 * health/metrics. Nothing about those registries changes: each {@code NvidiaProvider}
 * instance simply supplies a distinct {@link #name()} (its model key), exactly like
 * every other {@code LlmProvider} bean already does.</p>
 */
public class NvidiaProvider extends AbstractOpenAiChatProvider {

    private final String key;
    private final boolean enabledInNvidiaCluster;

    public NvidiaProvider(AiGatewayProperties props, String key) {
        super(props.provider(key));
        this.key = key;
        this.enabledInNvidiaCluster = props.getNvidia().isEnabled(key);
    }

    @Override public String name() { return key; }

    @Override public String displayName() {
        return cfg.getDisplayName() == null ? "NVIDIA (" + key + ")" : cfg.getDisplayName();
    }

    /**
     * Adds the {@code ai.gateway.nvidia.models[].enabled} kill switch on top of the usual
     * api-key/model presence check — lets an operator disable one NVIDIA model without
     * touching its own (or any other NVIDIA model's) API key.
     */
    @Override public boolean isConfigured() {
        return enabledInNvidiaCluster && super.isConfigured();
    }
}
