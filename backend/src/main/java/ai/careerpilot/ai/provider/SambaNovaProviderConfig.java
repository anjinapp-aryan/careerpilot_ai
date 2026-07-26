package ai.careerpilot.ai.provider;

import ai.careerpilot.ai.AiGatewayProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers one {@link SambaNovaProvider} bean per SambaNova-hosted model this
 * platform integrates with — same pattern as {@link NvidiaProviderConfig}. Each
 * bean becomes one more entry in {@code AiGatewayService}'s provider registry with
 * zero changes to the gateway/router/circuit-breaker/retry/health/metrics code.
 *
 * <p>Model membership mirrors the SambaNova model strategy: DeepSeek-V3.2 (primary),
 * DeepSeek-V3.1 (secondary — verified available but not assigned a named role),
 * gpt-oss-120b (reasoning), Meta-Llama-3.3-70B-Instruct (conversation),
 * gemma-4-31B-it (fast), MiniMax-M2.7 (fallback). Adding a further SambaNova model
 * is one more {@code @Bean} method here; enabling/disabling/reordering an existing
 * one is a config-only change via {@code ai.gateway.order} and {@code
 * ai.gateway.sambanova.models[].enabled}.</p>
 */
@Configuration
public class SambaNovaProviderConfig {

    /** Primary. */
    @Bean
    public SambaNovaProvider sambaNovaDeepSeekV32(AiGatewayProperties props) {
        return new SambaNovaProvider(props, "sambanova_deepseek_v3_2");
    }

    /** Secondary DeepSeek variant — verified available, no named strategy role. */
    @Bean
    public SambaNovaProvider sambaNovaDeepSeekV31(AiGatewayProperties props) {
        return new SambaNovaProvider(props, "sambanova_deepseek_v3_1");
    }

    /** Reasoning. */
    @Bean
    public SambaNovaProvider sambaNovaGptOss120b(AiGatewayProperties props) {
        return new SambaNovaProvider(props, "sambanova_gpt_oss_120b");
    }

    /** Conversation. */
    @Bean
    public SambaNovaProvider sambaNovaLlama33_70b(AiGatewayProperties props) {
        return new SambaNovaProvider(props, "sambanova_llama_3_3_70b");
    }

    /** Fast. */
    @Bean
    public SambaNovaProvider sambaNovaGemma431b(AiGatewayProperties props) {
        return new SambaNovaProvider(props, "sambanova_gemma_4_31b");
    }

    /** Fallback. */
    @Bean
    public SambaNovaProvider sambaNovaMinimaxM27(AiGatewayProperties props) {
        return new SambaNovaProvider(props, "sambanova_minimax_m2_7");
    }
}
