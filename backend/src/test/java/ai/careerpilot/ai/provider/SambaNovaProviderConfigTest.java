package ai.careerpilot.ai.provider;

import ai.careerpilot.ai.AiGatewayProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SambaNovaProviderConfig} registers one {@link SambaNovaProvider} bean per
 * SambaNova model — this checks it produces exactly the six expected model beans
 * (matching {@code ai.gateway.order}'s sambanova_* slots), all instances of the single
 * {@code SambaNovaProvider} class, mirroring {@link NvidiaProviderConfigTest}.
 */
class SambaNovaProviderConfigTest {

    @Test
    void producesOneBeanPerConfiguredSambaNovaModel_allTheSameClass() {
        SambaNovaProviderConfig config = new SambaNovaProviderConfig();
        AiGatewayProperties props = new AiGatewayProperties();

        List<SambaNovaProvider> beans = List.of(
                config.sambaNovaDeepSeekV32(props),
                config.sambaNovaDeepSeekV31(props),
                config.sambaNovaGptOss120b(props),
                config.sambaNovaLlama33_70b(props),
                config.sambaNovaGemma431b(props),
                config.sambaNovaMinimaxM27(props));

        assertThat(beans).hasSize(6);
        assertThat(beans).allMatch(b -> b.getClass() == SambaNovaProvider.class);
        assertThat(beans.stream().map(SambaNovaProvider::name).toList())
                .containsExactly(
                        "sambanova_deepseek_v3_2",
                        "sambanova_deepseek_v3_1",
                        "sambanova_gpt_oss_120b",
                        "sambanova_llama_3_3_70b",
                        "sambanova_gemma_4_31b",
                        "sambanova_minimax_m2_7");
    }
}
