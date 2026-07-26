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
    private String primary = "deepseek";

    /** Failover order of provider keys, highest priority first. */
    private List<String> order = new ArrayList<>(List.of("deepseek", "gemini", "groq", "qwen", "glm"));

    /** Default sampling temperature for chat/feature calls. */
    private double defaultTemperature = 0.4;

    /**
     * Phase A feature flag for the Enterprise Smart Router. When {@code false}
     * (the default), the gateway behaves exactly as before — pure sequential
     * failover over {@link #order}. The flag is loaded but NOT consulted by the
     * routing path in Phase A; task-aware routing (consuming {@link #routing})
     * is wired in a later phase. Bound to {@code AI_SMART_ROUTER_ENABLED}.
     */
    private boolean smartRouterEnabled = false;

    /**
     * Per-task provider preference lists (task name → ordered provider keys),
     * e.g. {@code resumeOptimization: [deepseek, gemini, glm]}. Loaded from
     * config so provider order per task can change without code, but only
     * consulted when {@link #smartRouterEnabled} is true (future phase). Empty
     * by default — has zero effect on current behavior.
     */
    private Map<String, List<String>> routing = new LinkedHashMap<>();

    /** Per-provider settings keyed by provider name (gemini | deepseek | qwen | …). */
    private Map<String, Provider> providers = new LinkedHashMap<>();

    /**
     * Descriptive/gating metadata for the NVIDIA NIM model cluster (deepseek_flash, deepseek —
     * all served by the single {@code NvidiaProvider} class, see {@code
     * ai/provider/NvidiaProvider.java} and {@code NvidiaProviderConfig}). Credentials/model
     * id/timeout for each of those keys still live in {@link #providers} exactly like every
     * other provider (unchanged mechanism) — this is additive, NVIDIA-specific metadata only:
     * {@code priority} documents each model's intended relative order within the NVIDIA
     * cluster (the actual routing order remains {@link #order}, so there is one real ordering
     * mechanism, not two competing ones), and {@code enabled} is an explicit kill switch a
     * model can be turned off with independent of whether its (shared) API key is set.
     * (Kimi/GLM were part of this cluster and are now fully removed — not disabled — per the
     * OpenRouter modernization pass that also removed them from every config/doc/test.)
     */
    private Nvidia nvidia = new Nvidia();

    /**
     * Descriptive/gating metadata for the SambaNova model cluster (DeepSeek-V3.2,
     * DeepSeek-V3.1, gpt-oss-120b, Meta-Llama-3.3-70B-Instruct, gemma-4-31B-it,
     * MiniMax-M2.7 — all served by the single {@code SambaNovaProvider} class, see
     * {@code ai/provider/SambaNovaProvider.java} and {@code SambaNovaProviderConfig}).
     * Same shape/semantics as {@link #nvidia} — kept as a structurally separate class
     * rather than reusing {@link Nvidia} to avoid a misleadingly-named field, at the
     * cost of a small amount of duplication (candidate for a future generic
     * {@code ModelCluster} extraction if a third vendor cluster is added).
     */
    private SambaNova sambaNova = new SambaNova();

    /**
     * Configuration for OpenRouter's dynamic, capability-based model pool (see {@code
     * ai/provider/OpenRouterProvider.java} and {@code OpenRouterModelRegistry}). Unlike {@link
     * #nvidia}/{@link #sambaNova} (a fixed, enumerated set of {@code @Bean}-registered models),
     * OpenRouter is a single provider bean ({@code name()="openrouter"}, one slot in {@link
     * #order} — OpenRouter is the last-resort fallback tier, reached once NVIDIA, Gemini,
     * Groq, and SambaNova have all failed) that internally selects from a pool of models
     * grouped by {@link ai.careerpilot.ai.Capability}, validated at startup (best-effort,
     * never blocks/fails startup) against OpenRouter's live {@code GET /v1/models} catalog.
     * No model name is ever hardcoded in Java — every model id referenced anywhere in this
     * pool comes from {@link #capabilities} below.
     */
    private OpenRouter openRouter = new OpenRouter();

    public String getPrimary() { return primary; }
    public void setPrimary(String primary) { this.primary = primary; }

    public List<String> getOrder() { return order; }
    public void setOrder(List<String> order) { this.order = order; }

    public boolean isSmartRouterEnabled() { return smartRouterEnabled; }
    public void setSmartRouterEnabled(boolean smartRouterEnabled) { this.smartRouterEnabled = smartRouterEnabled; }

    public Map<String, List<String>> getRouting() { return routing; }
    public void setRouting(Map<String, List<String>> routing) { this.routing = routing; }

    public double getDefaultTemperature() { return defaultTemperature; }
    public void setDefaultTemperature(double defaultTemperature) { this.defaultTemperature = defaultTemperature; }

    public Map<String, Provider> getProviders() { return providers; }
    public void setProviders(Map<String, Provider> providers) { this.providers = providers; }

    public Provider provider(String key) {
        return providers.getOrDefault(key, new Provider());
    }

    public Nvidia getNvidia() { return nvidia; }
    public void setNvidia(Nvidia nvidia) { this.nvidia = nvidia; }

    public SambaNova getSambaNova() { return sambaNova; }
    public void setSambaNova(SambaNova sambaNova) { this.sambaNova = sambaNova; }

    public OpenRouter getOpenRouter() { return openRouter; }
    public void setOpenRouter(OpenRouter openRouter) { this.openRouter = openRouter; }

    /** Settings for a single LLM provider. */
    public static class Provider {
        private String displayName;
        private String apiKey;
        private String model;
        private String baseUrl;
        private long timeoutMs = 20_000;
        /**
         * Operator-supplied cost label (e.g. "free", "low", "medium", "high") surfaced in
         * diagnostics. Never inferred/guessed — null/unset simply means "unknown" rather than
         * fabricating a value with no real source.
         */
        private String costTier;
        /**
         * Operator-supplied flag: does this provider's configured model do extended reasoning
         * (vs. a fast/instruct model)? Config-driven rather than pattern-matched off the model
         * name, so it stays correct across model swaps without a code change. Defaults false.
         */
        private boolean supportsReasoning = false;

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

        public String getCostTier() { return costTier; }
        public void setCostTier(String costTier) { this.costTier = costTier; }

        public boolean isSupportsReasoning() { return supportsReasoning; }
        public void setSupportsReasoning(boolean supportsReasoning) { this.supportsReasoning = supportsReasoning; }
    }

    /** NVIDIA NIM model-cluster metadata — see the {@link #nvidia} field javadoc. */
    public static class Nvidia {
        private List<NvidiaModel> models = new ArrayList<>();

        public List<NvidiaModel> getModels() { return models; }
        public void setModels(List<NvidiaModel> models) { this.models = models; }

        /** True unless a {@code models[]} entry exists for this id and explicitly disables it. */
        public boolean isEnabled(String id) {
            return models.stream()
                    .filter(m -> id.equals(m.getId()))
                    .findFirst()
                    .map(NvidiaModel::isEnabled)
                    .orElse(true);
        }
    }

    /** One entry in {@code ai.gateway.nvidia.models} — {id, priority, enabled}. */
    public static class NvidiaModel {
        private String id;
        private int priority;
        private boolean enabled = true;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public int getPriority() { return priority; }
        public void setPriority(int priority) { this.priority = priority; }

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    /** SambaNova model-cluster metadata — see the {@link #sambaNova} field javadoc. */
    public static class SambaNova {
        private List<SambaNovaModel> models = new ArrayList<>();

        public List<SambaNovaModel> getModels() { return models; }
        public void setModels(List<SambaNovaModel> models) { this.models = models; }

        /** True unless a {@code models[]} entry exists for this id and explicitly disables it. */
        public boolean isEnabled(String id) {
            return models.stream()
                    .filter(m -> id.equals(m.getId()))
                    .findFirst()
                    .map(SambaNovaModel::isEnabled)
                    .orElse(true);
        }
    }

    /** One entry in {@code ai.gateway.sambanova.models} — {id, priority, enabled}. */
    public static class SambaNovaModel {
        private String id;
        private int priority;
        private boolean enabled = true;

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public int getPriority() { return priority; }
        public void setPriority(int priority) { this.priority = priority; }

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    /** OpenRouter dynamic model-pool configuration — see the {@link #openRouter} field javadoc. */
    public static class OpenRouter {
        /** Master switch — false makes the "openrouter" provider report unconfigured. */
        private boolean enabled = true;
        /**
         * Whether to call OpenRouter's live {@code GET /v1/models} catalog at startup to
         * validate the configured {@link #capabilities} preferred lists. When false, every
         * configured model is trusted as-is (no live validation) — startup behavior either
         * way never blocks or fails on a discovery problem.
         */
        private boolean autoDiscoverModels = true;
        /** Reserved for a future periodic re-discovery job; currently discovery only runs once, at startup. */
        private boolean refreshModelsOnStartup = true;
        /** Whether to log a warning for a configured model that the live catalog doesn't contain. */
        private boolean validateConfiguredModels = true;
        /**
         * Capability name (matching {@link ai.careerpilot.ai.Capability}, case-insensitive,
         * e.g. {@code reasoning}, {@code coding}) → ordered list of preferred OpenRouter model
         * ids for that capability. This is the ONLY place OpenRouter model ids are ever
         * written — no Java class references a specific model name.
         */
        private Map<String, CapabilityPreference> capabilities = new LinkedHashMap<>();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public boolean isAutoDiscoverModels() { return autoDiscoverModels; }
        public void setAutoDiscoverModels(boolean autoDiscoverModels) { this.autoDiscoverModels = autoDiscoverModels; }

        public boolean isRefreshModelsOnStartup() { return refreshModelsOnStartup; }
        public void setRefreshModelsOnStartup(boolean refreshModelsOnStartup) { this.refreshModelsOnStartup = refreshModelsOnStartup; }

        public boolean isValidateConfiguredModels() { return validateConfiguredModels; }
        public void setValidateConfiguredModels(boolean validateConfiguredModels) { this.validateConfiguredModels = validateConfiguredModels; }

        public Map<String, CapabilityPreference> getCapabilities() { return capabilities; }
        public void setCapabilities(Map<String, CapabilityPreference> capabilities) { this.capabilities = capabilities; }
    }

    /** One capability's preferred model list — {@code ai.gateway.open-router.capabilities.<name>.preferred}. */
    public static class CapabilityPreference {
        private List<String> preferred = new ArrayList<>();

        public List<String> getPreferred() { return preferred; }
        public void setPreferred(List<String> preferred) { this.preferred = preferred; }
    }
}
