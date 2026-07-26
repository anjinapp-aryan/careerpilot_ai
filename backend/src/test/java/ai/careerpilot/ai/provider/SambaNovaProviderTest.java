package ai.careerpilot.ai.provider;

import ai.careerpilot.ai.AiGatewayProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SambaNova hosts multiple models (DeepSeek-V3.2, DeepSeek-V3.1, gpt-oss-120b,
 * Meta-Llama-3.3-70B-Instruct, gemma-4-31B-it, MiniMax-M2.7) behind one account/one
 * OpenAI-compatible endpoint — this covers the single {@link SambaNovaProvider} class
 * that serves all of them, mirroring {@link NvidiaProviderTest}.
 */
class SambaNovaProviderTest {

    private static AiGatewayProperties propsFor(String key, AiGatewayProperties.Provider cfg) {
        AiGatewayProperties props = new AiGatewayProperties();
        props.getProviders().put(key, cfg);
        return props;
    }

    private static AiGatewayProperties.Provider configuredProvider(String model) {
        AiGatewayProperties.Provider cfg = new AiGatewayProperties.Provider();
        cfg.setApiKey("sambanova-test-key");
        cfg.setModel(model);
        cfg.setBaseUrl("https://api.sambanova.ai/v1");
        return cfg;
    }

    @Test
    void oneClassServesEveryModel_onlyTheKeyAndConfigDiffer() {
        AiGatewayProperties props = new AiGatewayProperties();
        props.getProviders().put("sambanova_deepseek_v3_2", configuredProvider("DeepSeek-V3.2"));
        props.getProviders().put("sambanova_gemma_4_31b", configuredProvider("gemma-4-31B-it"));

        SambaNovaProvider primary = new SambaNovaProvider(props, "sambanova_deepseek_v3_2");
        SambaNovaProvider fast = new SambaNovaProvider(props, "sambanova_gemma_4_31b");

        assertThat(primary.getClass()).isEqualTo(fast.getClass()); // literally the same Java class
        assertThat(primary.name()).isEqualTo("sambanova_deepseek_v3_2");
        assertThat(fast.name()).isEqualTo("sambanova_gemma_4_31b");
    }

    @Test
    void notConfiguredWithoutApiKeyOrModel() {
        SambaNovaProvider provider = new SambaNovaProvider(
                propsFor("sambanova_minimax_m2_7", new AiGatewayProperties.Provider()), "sambanova_minimax_m2_7");
        assertThat(provider.isConfigured()).isFalse();
    }

    @Test
    void configuredOnceApiKeyAndModelArePresent() {
        SambaNovaProvider provider = new SambaNovaProvider(
                propsFor("sambanova_llama_3_3_70b", configuredProvider("Meta-Llama-3.3-70B-Instruct")),
                "sambanova_llama_3_3_70b");
        assertThat(provider.isConfigured()).isTrue();
    }

    @Test
    void sambaNovaModelsEnabledFlagIsAKillSwitchIndependentOfTheApiKey() {
        AiGatewayProperties props = new AiGatewayProperties();
        props.getProviders().put("sambanova_gpt_oss_120b", configuredProvider("gpt-oss-120b")); // valid key + model
        AiGatewayProperties.SambaNovaModel meta = new AiGatewayProperties.SambaNovaModel();
        meta.setId("sambanova_gpt_oss_120b");
        meta.setPriority(3);
        meta.setEnabled(false); // explicitly disabled despite having a valid key
        props.getSambaNova().getModels().add(meta);

        SambaNovaProvider provider = new SambaNovaProvider(props, "sambanova_gpt_oss_120b");

        assertThat(provider.isConfigured()).isFalse();
    }

    @Test
    void modelsNotListedInSambaNovaModelsDefaultToEnabled() {
        SambaNovaProvider provider = new SambaNovaProvider(
                propsFor("sambanova_deepseek_v3_1", configuredProvider("DeepSeek-V3.1")), "sambanova_deepseek_v3_1");
        assertThat(provider.isConfigured()).isTrue();
    }

    @Test
    void displayNameDefaultsToSambaNovaPlusKeyWhenNotConfigured() {
        SambaNovaProvider provider = new SambaNovaProvider(
                propsFor("sambanova_minimax_m2_7", new AiGatewayProperties.Provider()), "sambanova_minimax_m2_7");
        assertThat(provider.displayName()).isEqualTo("SambaNova (sambanova_minimax_m2_7)");
    }

    @Test
    void displayNameHonorsConfiguredOverride() {
        AiGatewayProperties.Provider cfg = configuredProvider("DeepSeek-V3.2");
        cfg.setDisplayName("SambaNova DeepSeek V3.2 (Primary)");

        SambaNovaProvider provider = new SambaNovaProvider(
                propsFor("sambanova_deepseek_v3_2", cfg), "sambanova_deepseek_v3_2");

        assertThat(provider.displayName()).isEqualTo("SambaNova DeepSeek V3.2 (Primary)");
    }
}
