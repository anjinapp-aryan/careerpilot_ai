package ai.careerpilot.ai.provider;

import ai.careerpilot.ai.AiGatewayProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NVIDIA hosts multiple models (DeepSeek Flash, DeepSeek Pro, Kimi K2, GLM) behind one
 * account/one OpenAI-compatible endpoint — this covers the single {@link NvidiaProvider}
 * class that now serves all of them (replacing the previous per-model
 * DeepSeekFlashProvider/NvidiaDeepSeekProvider/GlmProvider classes), parameterized purely
 * by which config key it was built from.
 */
class NvidiaProviderTest {

    private static AiGatewayProperties propsFor(String key, AiGatewayProperties.Provider cfg) {
        AiGatewayProperties props = new AiGatewayProperties();
        props.getProviders().put(key, cfg);
        return props;
    }

    private static AiGatewayProperties.Provider configuredProvider(String model) {
        AiGatewayProperties.Provider cfg = new AiGatewayProperties.Provider();
        cfg.setApiKey("nvapi-test-key");
        cfg.setModel(model);
        cfg.setBaseUrl("https://integrate.api.nvidia.com/v1");
        return cfg;
    }

    @Test
    void oneClassServesEveryNvidiaModel_onlyTheKeyAndConfigDiffer() {
        AiGatewayProperties props = new AiGatewayProperties();
        props.getProviders().put("deepseek_flash", configuredProvider("deepseek-ai/deepseek-v4-flash"));
        props.getProviders().put("kimi", configuredProvider("moonshotai/kimi-k2.6"));

        NvidiaProvider flash = new NvidiaProvider(props, "deepseek_flash");
        NvidiaProvider kimi = new NvidiaProvider(props, "kimi");

        assertThat(flash.getClass()).isEqualTo(kimi.getClass()); // literally the same Java class
        assertThat(flash.name()).isEqualTo("deepseek_flash");
        assertThat(kimi.name()).isEqualTo("kimi");
    }

    @Test
    void notConfiguredWithoutApiKeyOrModel() {
        NvidiaProvider provider = new NvidiaProvider(propsFor("glm", new AiGatewayProperties.Provider()), "glm");
        assertThat(provider.isConfigured()).isFalse();
    }

    @Test
    void configuredOnceApiKeyAndModelArePresent() {
        NvidiaProvider provider = new NvidiaProvider(
                propsFor("deepseek", configuredProvider("deepseek-ai/deepseek-v4-pro")), "deepseek");
        assertThat(provider.isConfigured()).isTrue();
    }

    @Test
    void nvidiaModelsEnabledFlagIsAKillSwitchIndependentOfTheApiKey() {
        AiGatewayProperties props = new AiGatewayProperties();
        props.getProviders().put("kimi", configuredProvider("moonshotai/kimi-k2.6")); // valid api-key + model
        AiGatewayProperties.NvidiaModel kimiMeta = new AiGatewayProperties.NvidiaModel();
        kimiMeta.setId("kimi");
        kimiMeta.setPriority(3);
        kimiMeta.setEnabled(false); // explicitly disabled despite having a valid key
        props.getNvidia().getModels().add(kimiMeta);

        NvidiaProvider provider = new NvidiaProvider(props, "kimi");

        assertThat(provider.isConfigured()).isFalse();
    }

    @Test
    void modelsNotListedInNvidiaModelsDefaultToEnabled() {
        // No ai.gateway.nvidia.models entry for "glm" at all — must not be treated as disabled.
        NvidiaProvider provider = new NvidiaProvider(
                propsFor("glm", configuredProvider("z-ai/glm-5.2")), "glm");
        assertThat(provider.isConfigured()).isTrue();
    }

    @Test
    void displayNameDefaultsToNvidiaPlusKeyWhenNotConfigured() {
        NvidiaProvider provider = new NvidiaProvider(propsFor("glm", new AiGatewayProperties.Provider()), "glm");
        assertThat(provider.displayName()).isEqualTo("NVIDIA (glm)");
    }

    @Test
    void displayNameHonorsConfiguredOverride() {
        AiGatewayProperties.Provider cfg = configuredProvider("moonshotai/kimi-k2.6");
        cfg.setDisplayName("Kimi K2 (NVIDIA NIM)");

        NvidiaProvider provider = new NvidiaProvider(propsFor("kimi", cfg), "kimi");

        assertThat(provider.displayName()).isEqualTo("Kimi K2 (NVIDIA NIM)");
    }
}
