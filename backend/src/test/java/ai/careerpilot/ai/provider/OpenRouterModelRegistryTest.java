package ai.careerpilot.ai.provider;

import ai.careerpilot.ai.AiGatewayProperties;
import ai.careerpilot.ai.Capability;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Covers the parts of {@link OpenRouterModelRegistry} that don't require a live network call
 * (discovery itself is exercised manually/in staging — see the session's live OpenRouter
 * catalog probes). The critical property under test is graceful degradation: a
 * never-run/failed/disabled discovery must never prevent the configured preferred lists from
 * being used, and must never throw.
 */
class OpenRouterModelRegistryTest {

    private static AiGatewayProperties propsWith(String capability, List<String> models) {
        AiGatewayProperties props = new AiGatewayProperties();
        AiGatewayProperties.CapabilityPreference pref = new AiGatewayProperties.CapabilityPreference();
        pref.setPreferred(models);
        props.getOpenRouter().getCapabilities().put(capability, pref);
        return props;
    }

    @Test
    void beforeDiscoveryRuns_candidatesForReturnsTheRawConfiguredListUnfiltered() {
        AiGatewayProperties props = propsWith("reasoning", List.of("deepseek/deepseek-v4-flash", "nvidia/nemotron-3-ultra:free"));
        OpenRouterModelRegistry registry = new OpenRouterModelRegistry(props);

        assertThat(registry.candidatesFor(Capability.REASONING))
                .containsExactly("deepseek/deepseek-v4-flash", "nvidia/nemotron-3-ultra:free");
        assertThat(registry.discoverySucceeded()).isFalse();
        assertThat(registry.catalogSize()).isZero();
    }

    @Test
    void capabilityWithNoConfiguredModelsReturnsEmptyList() {
        AiGatewayProperties props = new AiGatewayProperties();
        OpenRouterModelRegistry registry = new OpenRouterModelRegistry(props);

        assertThat(registry.candidatesFor(Capability.VISION)).isEmpty();
    }

    @Test
    void allConfiguredModelsDeduplicated_unionsAcrossCapabilitiesPreservingOrderAndDedup() {
        AiGatewayProperties props = new AiGatewayProperties();
        AiGatewayProperties.CapabilityPreference reasoning = new AiGatewayProperties.CapabilityPreference();
        reasoning.setPreferred(List.of("deepseek/deepseek-v4-flash", "shared/model"));
        props.getOpenRouter().getCapabilities().put("reasoning", reasoning);

        AiGatewayProperties.CapabilityPreference chat = new AiGatewayProperties.CapabilityPreference();
        chat.setPreferred(List.of("shared/model", "google/gemma-4-26b-a4b-it:free")); // "shared/model" repeated
        props.getOpenRouter().getCapabilities().put("chat", chat);

        OpenRouterModelRegistry registry = new OpenRouterModelRegistry(props);

        assertThat(registry.allConfiguredModelsDeduplicated())
                .containsExactly("deepseek/deepseek-v4-flash", "shared/model", "google/gemma-4-26b-a4b-it:free");
    }

    @Test
    void metadataForUnknownModelIsEmpty_neverThrows() {
        OpenRouterModelRegistry registry = new OpenRouterModelRegistry(new AiGatewayProperties());
        assertThat(registry.metadataFor("nonexistent/model")).isEmpty();
    }

    @Test
    void discoveryDisabledInConfig_refreshCatalogIsNeverCalled_registryStaysUsable() {
        // discoverOnStartup() checks autoDiscoverModels and returns early without any network
        // call when false — verifying it doesn't throw and the registry remains fully usable.
        AiGatewayProperties props = propsWith("coding", List.of("cohere/north-mini-code:free"));
        props.getOpenRouter().setAutoDiscoverModels(false);
        OpenRouterModelRegistry registry = new OpenRouterModelRegistry(props);

        assertThatNoException().isThrownBy(registry::discoverOnStartup);
        assertThat(registry.candidatesFor(Capability.CODING)).containsExactly("cohere/north-mini-code:free");
    }

    @Test
    void refreshCatalogWithNoApiKeyConfigured_skipsGracefullyWithoutThrowing() {
        // No OPENROUTER_API_KEY set — refreshCatalog() must skip (not attempt an unauthenticated
        // call) and never throw, per the "missing config must never break startup" guarantee.
        OpenRouterModelRegistry registry = new OpenRouterModelRegistry(new AiGatewayProperties());
        assertThatNoException().isThrownBy(registry::refreshCatalog);
        assertThat(registry.discoverySucceeded()).isFalse();
    }
}
