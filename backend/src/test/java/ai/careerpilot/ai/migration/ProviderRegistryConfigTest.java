package ai.careerpilot.ai.migration;

import ai.careerpilot.ai.AiGatewayProperties;
import ai.careerpilot.ai.LlmProvider;
import ai.careerpilot.ai.provider.GeminiProvider;
import ai.careerpilot.ai.provider.GeminiSpringAiProvider;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 10.2.1 validation sprint — {@link ProviderRegistryConfig} (Phase 9.3's legacy/Spring-AI
 * switch) had no regression test; verified only via a temporary, deleted smoke test at the time.
 * This closes that gap: confirms the flag genuinely switches which {@link LlmProvider}
 * implementation gets registered for the {@code "gemini"} key, and that both implementations
 * report the same {@code name()} (so {@code ai.gateway.order}'s "gemini" slot resolves
 * identically regardless of which engine is behind it — the whole point of the migration
 * pattern).
 */
class ProviderRegistryConfigTest {

    private final ProviderRegistryConfig config = new ProviderRegistryConfig();

    @Test
    void geminiFlagOff_resolvesToLegacyProvider() {
        SpringAiMigrationProperties migrationProps = new SpringAiMigrationProperties();
        migrationProps.getGemini().setEnabled(false);

        LlmProvider provider = config.geminiLlmProvider(migrationProps, new AiGatewayProperties());

        assertThat(provider).isInstanceOf(GeminiProvider.class);
        assertThat(provider.name()).isEqualTo("gemini");
    }

    @Test
    void geminiFlagOn_resolvesToSpringAiProvider() {
        SpringAiMigrationProperties migrationProps = new SpringAiMigrationProperties();
        migrationProps.getGemini().setEnabled(true);

        LlmProvider provider = config.geminiLlmProvider(migrationProps, new AiGatewayProperties());

        assertThat(provider).isInstanceOf(GeminiSpringAiProvider.class);
        assertThat(provider.name()).isEqualTo("gemini");
    }

    @Test
    void bothImplementationsShareTheSameProviderKey_soRoutingOrderIsUnaffected() {
        SpringAiMigrationProperties off = new SpringAiMigrationProperties();
        SpringAiMigrationProperties on = new SpringAiMigrationProperties();
        on.getGemini().setEnabled(true);

        String legacyName = config.geminiLlmProvider(off, new AiGatewayProperties()).name();
        String springAiName = config.geminiLlmProvider(on, new AiGatewayProperties()).name();

        assertThat(legacyName).isEqualTo(springAiName);
    }

    @Test
    void displayNamesDifferSoDiagnosticsCanDistinguishTheEngine() {
        SpringAiMigrationProperties on = new SpringAiMigrationProperties();
        on.getGemini().setEnabled(true);

        String legacyDisplayName = config.geminiLlmProvider(new SpringAiMigrationProperties(), new AiGatewayProperties()).displayName();
        String springAiDisplayName = config.geminiLlmProvider(on, new AiGatewayProperties()).displayName();

        assertThat(legacyDisplayName).isNotEqualTo(springAiDisplayName);
    }
}
