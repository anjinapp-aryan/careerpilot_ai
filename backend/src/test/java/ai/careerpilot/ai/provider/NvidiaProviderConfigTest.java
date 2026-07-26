package ai.careerpilot.ai.provider;

import ai.careerpilot.ai.AiGatewayProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link NvidiaProviderConfig} is what registers one {@link NvidiaProvider} bean per NVIDIA
 * NIM model — this checks it produces exactly the two expected model beans (matching {@code
 * ai.gateway.order}'s deepseek_flash/deepseek slots), all instances of the single {@code
 * NvidiaProvider} class. Kimi and GLM were fully removed from this config class (not merely
 * disabled) as part of the OpenRouter modernization pass — see {@code
 * OpenRouterProviderConfigTest} for their enterprise-grade replacements.
 */
class NvidiaProviderConfigTest {

    @Test
    void producesOneBeanPerConfiguredNvidiaModel_allTheSameClass() {
        NvidiaProviderConfig config = new NvidiaProviderConfig();
        AiGatewayProperties props = new AiGatewayProperties();

        List<NvidiaProvider> beans = List.of(
                config.nvidiaDeepSeekFlash(props),
                config.nvidiaDeepSeekPro(props));

        assertThat(beans).hasSize(2);
        assertThat(beans).allMatch(b -> b.getClass() == NvidiaProvider.class);
        assertThat(beans.stream().map(NvidiaProvider::name).toList())
                .containsExactly("deepseek_flash", "deepseek");
    }

    @Test
    void kimiAndGlmAreNotRegisteredAtAll() {
        // Kimi/GLM removal per the OpenRouter modernization pass — no @Bean method should
        // produce a provider named "kimi" or "glm" anywhere in this config class.
        NvidiaProviderConfig config = new NvidiaProviderConfig();
        AiGatewayProperties props = new AiGatewayProperties();

        List<String> names = List.of(
                config.nvidiaDeepSeekFlash(props).name(),
                config.nvidiaDeepSeekPro(props).name());

        assertThat(names).doesNotContain("kimi", "glm");
    }

    @Test
    void deepseekProKeyStaysDeepseek_notDeepseekV4_forBackwardCompatibility() {
        // "deepseek" is referenced by resume.tailoring.preferred-providers, ai.gateway.routing.*,
        // and diagnostics health checks elsewhere in the codebase — renaming it to "deepseek_v4"
        // would be a breaking config change, so the bean must keep the original key.
        NvidiaProviderConfig config = new NvidiaProviderConfig();
        NvidiaProvider deepSeekPro = config.nvidiaDeepSeekPro(new AiGatewayProperties());
        assertThat(deepSeekPro.name()).isEqualTo("deepseek");
    }
}
