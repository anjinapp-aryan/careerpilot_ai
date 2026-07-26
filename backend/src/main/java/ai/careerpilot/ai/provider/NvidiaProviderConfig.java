package ai.careerpilot.ai.provider;

import ai.careerpilot.ai.AiGatewayProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers one {@link NvidiaProvider} bean per NVIDIA NIM-hosted model this platform
 * integrates with. Each bean becomes one more entry in {@code AiGatewayService}'s
 * provider registry — a {@code List<LlmProvider>} injection point Spring populates
 * from every {@code LlmProvider} bean in the context — so the gateway, router, circuit
 * breaker, retry, health, and metrics code needs zero changes to pick these up,
 * exactly like the existing {@code @Component} providers ({@link GroqProvider} etc.).
 *
 * <p>Model membership is a fixed list here (currently two NVIDIA models — Kimi and GLM
 * were removed entirely, not disabled, as part of the OpenRouter modernization pass;
 * see {@code OpenRouterProviderConfig} for their enterprise-grade replacements) rather
 * than reflectively built from an arbitrary-length config list, because Spring bean
 * registration needs a known bean count at context-startup time — the same convention
 * every other provider in this package already follows (one class per known provider).
 * Adding another NVIDIA model is one more {@code @Bean} method here;
 * enabling/disabling/reordering an existing one is a config-only change via {@code
 * ai.gateway.order} and {@code ai.gateway.nvidia.models[].enabled} — no other Java
 * file changes either way.</p>
 */
@Configuration
public class NvidiaProviderConfig {

    /** Priority 1 — fast/cheap DeepSeek variant. */
    @Bean
    public NvidiaProvider nvidiaDeepSeekFlash(AiGatewayProperties props) {
        return new NvidiaProvider(props, "deepseek_flash");
    }

    /** Priority 2 — DeepSeek pro/reasoning variant. Key stays "deepseek" (not "deepseek_v4")
     *  for backward compatibility with existing references (resume.tailoring.preferred-providers,
     *  ai.gateway.routing.*, diagnostics health checks all already name it "deepseek"). */
    @Bean
    public NvidiaProvider nvidiaDeepSeekPro(AiGatewayProperties props) {
        return new NvidiaProvider(props, "deepseek");
    }
}
