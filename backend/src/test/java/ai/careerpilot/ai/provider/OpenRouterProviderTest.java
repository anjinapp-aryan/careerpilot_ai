package ai.careerpilot.ai.provider;

import ai.careerpilot.ai.AiGatewayException;
import ai.careerpilot.ai.AiGatewayProperties;
import ai.careerpilot.ai.AiMetrics;
import ai.careerpilot.ai.Capability;
import ai.careerpilot.ai.ChatMessage;
import ai.careerpilot.ai.ProviderHealthTracker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OpenRouter is modeled as a single provider bean fronting a <em>pool</em> of models,
 * selected per-call via {@link Capability} (config-driven, never a hardcoded model name in
 * Java — see {@link OpenRouterModelRegistry}). No real network calls here: {@code
 * chatWithModel}/{@code streamChatWithModel} (from {@link AbstractOpenAiChatProvider}) are
 * overridden by a test subclass, the same technique {@code AiGatewayRoutingTest}'s
 * {@code FakeLlmProvider} uses for the outer gateway.
 */
class OpenRouterProviderTest {

    /** Deterministic test double — overrides the real-HTTP methods only. */
    private static class TestableOpenRouterProvider extends OpenRouterProvider {
        private final Map<String, Boolean> failingModels; // modelId -> true if it should throw
        final AtomicInteger callCount = new AtomicInteger();

        TestableOpenRouterProvider(AiGatewayProperties props, OpenRouterModelRegistry registry,
                                    RetryRegistry retryRegistry, CircuitBreakerRegistry cbRegistry,
                                    AiMetrics metrics, ProviderHealthTracker healthTracker,
                                    Map<String, Boolean> failingModels) {
            super(props, registry, retryRegistry, cbRegistry, metrics, healthTracker);
            this.failingModels = failingModels;
        }

        @Override
        protected String chatWithModel(List<ChatMessage> messages, String system, double temperature, String model) {
            callCount.incrementAndGet();
            if (failingModels.getOrDefault(model, false)) {
                throw new IllegalStateException(model + " is down");
            }
            return model + "-response";
        }
    }

    private static AiGatewayProperties propsWithCapability(String capabilityName, List<String> models) {
        AiGatewayProperties props = new AiGatewayProperties();
        AiGatewayProperties.Provider cfg = new AiGatewayProperties.Provider();
        cfg.setApiKey("openrouter-test-key");
        props.getProviders().put(OpenRouterProvider.KEY, cfg);

        AiGatewayProperties.CapabilityPreference pref = new AiGatewayProperties.CapabilityPreference();
        pref.setPreferred(models);
        props.getOpenRouter().getCapabilities().put(capabilityName, pref);
        return props;
    }

    private static TestableOpenRouterProvider providerFor(AiGatewayProperties props, Map<String, Boolean> failingModels) {
        RetryRegistry retryRegistry = RetryRegistry.of(RetryConfig.custom().maxAttempts(1).build());
        CircuitBreakerRegistry cbRegistry = CircuitBreakerRegistry.of(
                CircuitBreakerConfig.custom().minimumNumberOfCalls(50).build());
        OpenRouterModelRegistry registry = new OpenRouterModelRegistry(props); // discovery never triggered — network-free
        return new TestableOpenRouterProvider(props, registry, retryRegistry, cbRegistry,
                new AiMetrics(), new ProviderHealthTracker(), failingModels);
    }

    @Test
    void notConfiguredWithoutApiKey() {
        AiGatewayProperties props = propsWithCapability("reasoning", List.of("deepseek/deepseek-v4-flash"));
        props.getProviders().get(OpenRouterProvider.KEY).setApiKey(null);
        assertThat(providerFor(props, Map.of()).isConfigured()).isFalse();
    }

    @Test
    void notConfiguredWithoutAnyModelsAcrossAllCapabilities() {
        AiGatewayProperties props = new AiGatewayProperties();
        AiGatewayProperties.Provider cfg = new AiGatewayProperties.Provider();
        cfg.setApiKey("openrouter-test-key");
        props.getProviders().put(OpenRouterProvider.KEY, cfg);
        assertThat(providerFor(props, Map.of()).isConfigured()).isFalse();
    }

    @Test
    void configuredOnceApiKeyAndAtLeastOneModelArePresent() {
        AiGatewayProperties props = propsWithCapability("chat", List.of("google/gemma-4-26b-a4b-it:free"));
        assertThat(providerFor(props, Map.of()).isConfigured()).isTrue();
    }

    @Test
    void masterEnabledFlagOverridesEverythingElse() {
        AiGatewayProperties props = propsWithCapability("reasoning", List.of("deepseek/deepseek-v4-flash"));
        props.getOpenRouter().setEnabled(false);
        assertThat(providerFor(props, Map.of()).isConfigured()).isFalse();
    }

    @Test
    void chatByCapabilityUsesTheFirstHealthyModelInTheConfiguredCandidateOrder() {
        AiGatewayProperties props = propsWithCapability("reasoning",
                List.of("deepseek/deepseek-v4-flash", "nvidia/nemotron-3-ultra-550b-a55b:free"));
        TestableOpenRouterProvider provider = providerFor(props, Map.of());

        String result = provider.chatByCapability(List.of(ChatMessage.user("hi")), "sys", 0.4, Capability.REASONING);

        assertThat(result).isEqualTo("deepseek/deepseek-v4-flash-response");
        assertThat(provider.callCount.get()).isEqualTo(1); // second model never tried
    }

    @Test
    void chatByCapabilityFailsOverToTheNextModelOnFailure() {
        AiGatewayProperties props = propsWithCapability("reasoning",
                List.of("deepseek/deepseek-v4-flash", "nvidia/nemotron-3-ultra-550b-a55b:free"));
        TestableOpenRouterProvider provider = providerFor(props,
                Map.of("deepseek/deepseek-v4-flash", true)); // first model fails

        String result = provider.chatByCapability(List.of(ChatMessage.user("hi")), "sys", 0.4, Capability.REASONING);

        assertThat(result).isEqualTo("nvidia/nemotron-3-ultra-550b-a55b:free-response");
        assertThat(provider.callCount.get()).isEqualTo(2);
    }

    @Test
    void chatByCapabilityThrowsWhenEveryModelInThePoolFails() {
        AiGatewayProperties props = propsWithCapability("coding", List.of("cohere/north-mini-code:free"));
        TestableOpenRouterProvider provider = providerFor(props, Map.of("cohere/north-mini-code:free", true));

        assertThatThrownBy(() -> provider.chatByCapability(List.of(ChatMessage.user("hi")), "sys", 0.4, Capability.CODING))
                .isInstanceOf(AiGatewayException.class);
    }

    @Test
    void chatByCapabilityThrowsImmediatelyWhenNoCandidateConfiguredForThatCapability() {
        AiGatewayProperties props = propsWithCapability("reasoning", List.of("deepseek/deepseek-v4-flash"));
        TestableOpenRouterProvider provider = providerFor(props, Map.of());

        // No "vision" capability configured at all.
        assertThatThrownBy(() -> provider.chatByCapability(List.of(ChatMessage.user("hi")), "sys", 0.4, Capability.VISION))
                .isInstanceOf(AiGatewayException.class);
        assertThat(provider.callCount.get()).isZero();
    }

    @Test
    void noCapabilitySpecified_triesTheUnionOfAllConfiguredModels() {
        AiGatewayProperties props = new AiGatewayProperties();
        AiGatewayProperties.Provider cfg = new AiGatewayProperties.Provider();
        cfg.setApiKey("openrouter-test-key");
        props.getProviders().put(OpenRouterProvider.KEY, cfg);

        AiGatewayProperties.CapabilityPreference reasoning = new AiGatewayProperties.CapabilityPreference();
        reasoning.setPreferred(List.of("deepseek/deepseek-v4-flash"));
        props.getOpenRouter().getCapabilities().put("reasoning", reasoning);

        AiGatewayProperties.CapabilityPreference coding = new AiGatewayProperties.CapabilityPreference();
        coding.setPreferred(List.of("cohere/north-mini-code:free"));
        props.getOpenRouter().getCapabilities().put("coding", coding);

        TestableOpenRouterProvider provider = providerFor(props, Map.of("deepseek/deepseek-v4-flash", true));

        String result = provider.chat(List.of(ChatMessage.user("hi")), "sys", 0.4); // no capability = chat()

        assertThat(result).isEqualTo("cohere/north-mini-code:free-response");
    }

    @Test
    void modelPoolStatusesListsEveryConfiguredModelAcrossAllCapabilities() {
        AiGatewayProperties props = propsWithCapability("lightweight",
                List.of("openai/gpt-oss-20b:free", "nvidia/nemotron-3-nano-30b-a3b:free"));
        TestableOpenRouterProvider provider = providerFor(props, Map.of());

        List<Map<String, Object>> statuses = provider.modelPoolStatuses();

        assertThat(statuses).extracting(m -> m.get("id"))
                .containsExactly("openai/gpt-oss-20b:free", "nvidia/nemotron-3-nano-30b-a3b:free");
        assertThat(statuses).allMatch(m -> m.containsKey("circuitState") && m.containsKey("health")
                && m.containsKey("avgLatencyMs") && m.containsKey("inCatalog"));
    }

    @Test
    void displayNameDefaultsToOpenRouter() {
        AiGatewayProperties props = propsWithCapability("chat", List.of("google/gemma-4-26b-a4b-it:free"));
        assertThat(providerFor(props, Map.of()).displayName()).isEqualTo("OpenRouter");
    }

    @Test
    void nameIsAlwaysTheSingleOuterOrderKey() {
        AiGatewayProperties props = propsWithCapability("chat", List.of("google/gemma-4-26b-a4b-it:free"));
        assertThat(providerFor(props, Map.of()).name()).isEqualTo("openrouter");
    }
}
