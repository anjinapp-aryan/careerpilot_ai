package ai.careerpilot.ai;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers the config-driven priority model added for the 7-provider routing upgrade
 * (deepseek_flash, deepseek, groq, glm, gemini, qwen, openrouter): provider order comes
 * entirely from {@link AiGatewayProperties#getOrder()} (no hardcoded Java ordering),
 * disabled/unconfigured providers are skipped, failure triggers transparent failover to
 * the next configured provider, and {@link AiGatewayService#providerStatuses()} reports
 * the extended diagnostics metadata (priority, retryCount, costTier, supportsStreaming,
 * supportsReasoning) without disturbing the existing fields other callers already read.
 */
class AiGatewayRoutingTest {

    /** Deterministic test double — no network calls, fully controllable per test. */
    private static class FakeLlmProvider extends AbstractLlmProvider {
        private final String key;
        private final boolean configured;
        private final boolean throwsOnChat;
        final AtomicInteger chatCalls = new AtomicInteger();

        FakeLlmProvider(String key, boolean configured, boolean throwsOnChat) {
            this.key = key;
            this.configured = configured;
            this.throwsOnChat = throwsOnChat;
        }

        @Override public String name() { return key; }
        @Override public String displayName() { return key; }
        @Override public boolean isConfigured() { return configured; }
        @Override public Duration timeout() { return Duration.ofSeconds(5); }

        @Override
        public String chat(List<ChatMessage> messages, String system, double temperature) {
            chatCalls.incrementAndGet();
            if (throwsOnChat) {
                throw new IllegalStateException(key + " is down");
            }
            return key + "-response";
        }

        @Override
        public Flux<String> streamChat(List<ChatMessage> messages, String system, double temperature) {
            return throwsOnChat ? Flux.error(new IllegalStateException(key + " is down")) : Flux.just(key);
        }
    }

    private static AiGatewayService gatewayOf(AiGatewayProperties props, FakeLlmProvider... providers) {
        RetryRegistry retryRegistry = RetryRegistry.of(RetryConfig.custom().maxAttempts(3).build());
        CircuitBreakerRegistry cbRegistry = CircuitBreakerRegistry.of(
                CircuitBreakerConfig.custom().minimumNumberOfCalls(50).build()); // effectively never opens mid-test
        return new AiGatewayService(List.of(providers), props, new AiMetrics(), retryRegistry, cbRegistry,
                new ProviderHealthTracker());
    }

    private static AiGatewayProperties.Provider providerConfig(String costTier, boolean reasoning, long timeoutMs) {
        AiGatewayProperties.Provider cfg = new AiGatewayProperties.Provider();
        cfg.setModel("some-model");
        cfg.setCostTier(costTier);
        cfg.setSupportsReasoning(reasoning);
        cfg.setTimeoutMs(timeoutMs);
        return cfg;
    }

    @Test
    void orderIsReadEntirelyFromConfiguration_noHardcodedJavaOrdering() {
        AiGatewayProperties props = new AiGatewayProperties();
        props.setOrder(List.of("z_provider", "a_provider")); // deliberately not alphabetical/insertion order
        props.getProviders().put("z_provider", providerConfig(null, false, 1000));
        props.getProviders().put("a_provider", providerConfig(null, false, 1000));

        AiGatewayService gateway = gatewayOf(props,
                new FakeLlmProvider("z_provider", true, false),
                new FakeLlmProvider("a_provider", true, false));

        List<String> namesInOrder = gateway.providerStatuses().stream().map(m -> (String) m.get("name")).toList();
        assertThat(namesInOrder).containsExactly("z_provider", "a_provider");
    }

    @Test
    void disabledUnconfiguredProviderIsSkippedByRoutingAndReportedNotConfigured() {
        AiGatewayProperties props = new AiGatewayProperties();
        props.setOrder(List.of("primary", "disabled", "secondary"));
        props.getProviders().put("primary", providerConfig(null, false, 1000));
        props.getProviders().put("disabled", providerConfig(null, false, 1000));
        props.getProviders().put("secondary", providerConfig(null, false, 1000));

        FakeLlmProvider primary = new FakeLlmProvider("primary", true, true); // fails, forces skip past "disabled"
        FakeLlmProvider disabled = new FakeLlmProvider("disabled", false, false); // not configured — must be skipped
        FakeLlmProvider secondary = new FakeLlmProvider("secondary", true, false);
        AiGatewayService gateway = gatewayOf(props, primary, disabled, secondary);

        String result = gateway.chat(List.of(ChatMessage.user("hi")), "sys");

        assertThat(result).isEqualTo("secondary-response");
        assertThat(disabled.chatCalls.get()).isZero(); // never invoked — isConfigured()=false excludes it entirely

        Map<String, Object> disabledStatus = gateway.providerStatuses().stream()
                .filter(m -> "disabled".equals(m.get("name"))).findFirst().orElseThrow();
        assertThat(disabledStatus.get("status")).isEqualTo("NOT_CONFIGURED");
        assertThat(disabledStatus.get("enabled")).isEqualTo(false);
    }

    @Test
    void failoverMovesToNextConfiguredProviderOnFailure_untilOneSucceeds() {
        AiGatewayProperties props = new AiGatewayProperties();
        props.setOrder(List.of("deepseek_flash", "deepseek", "gemini"));
        props.getProviders().put("deepseek_flash", providerConfig("low", false, 15000));
        props.getProviders().put("deepseek", providerConfig("medium", true, 20000));
        props.getProviders().put("gemini", providerConfig("low", false, 15000));

        FakeLlmProvider flash = new FakeLlmProvider("deepseek_flash", true, true);
        FakeLlmProvider pro = new FakeLlmProvider("deepseek", true, false);
        FakeLlmProvider gemini = new FakeLlmProvider("gemini", true, false);
        AiGatewayService gateway = gatewayOf(props, flash, pro, gemini);

        String result = gateway.chat(List.of(ChatMessage.user("hi")), "sys");

        assertThat(result).isEqualTo("deepseek-response");
        // Resilience4j retries the failing provider up to maxAttempts (3) before AiGatewayService
        // moves to the next provider — that per-provider retry budget is existing, reused behavior.
        assertThat(flash.chatCalls.get()).isEqualTo(3);
        assertThat(pro.chatCalls.get()).isEqualTo(1);
        assertThat(gemini.chatCalls.get()).isZero(); // failover stops once "deepseek" succeeds
    }

    @Test
    void allProvidersFailingThrowsAiGatewayException() {
        AiGatewayProperties props = new AiGatewayProperties();
        props.setOrder(List.of("only"));
        props.getProviders().put("only", providerConfig(null, false, 1000));
        AiGatewayService gateway = gatewayOf(props, new FakeLlmProvider("only", true, true));

        assertThatThrownBy(() -> gateway.chat(List.of(ChatMessage.user("hi")), "sys"))
                .isInstanceOf(AiGatewayException.class);
    }

    @Test
    void providerStatusesExposeExtendedRoutingMetadata() {
        AiGatewayProperties props = new AiGatewayProperties();
        props.setOrder(List.of("deepseek_flash", "glm"));
        props.getProviders().put("deepseek_flash", providerConfig("low", false, 15000));
        props.getProviders().put("glm", providerConfig("medium", true, 20000));

        AiGatewayService gateway = gatewayOf(props,
                new FakeLlmProvider("deepseek_flash", true, false),
                new FakeLlmProvider("glm", false, false));

        Map<String, Object> flashStatus = gateway.providerStatuses().get(0);
        assertThat(flashStatus.get("name")).isEqualTo("deepseek_flash");
        assertThat(flashStatus.get("priority")).isEqualTo(1);
        assertThat(flashStatus.get("timeoutMs")).isEqualTo(15000L);
        assertThat(flashStatus.get("retryCount")).isEqualTo(3);
        assertThat(flashStatus.get("costTier")).isEqualTo("low");
        assertThat(flashStatus.get("supportsStreaming")).isEqualTo(true);
        assertThat(flashStatus.get("supportsReasoning")).isEqualTo(false);

        Map<String, Object> glmStatus = gateway.providerStatuses().get(1);
        assertThat(glmStatus.get("priority")).isEqualTo(2);
        assertThat(glmStatus.get("costTier")).isEqualTo("medium");
        assertThat(glmStatus.get("supportsReasoning")).isEqualTo(true);
        assertThat(glmStatus.get("enabled")).isEqualTo(false); // unconfigured — placeholder key only
    }

    @Test
    void costTierDefaultsToUnknown_neverFabricated() {
        AiGatewayProperties props = new AiGatewayProperties();
        props.setOrder(List.of("no_cost_tier_set"));
        props.getProviders().put("no_cost_tier_set", providerConfig(null, false, 1000));
        AiGatewayService gateway = gatewayOf(props, new FakeLlmProvider("no_cost_tier_set", true, false));

        assertThat(gateway.providerStatuses().get(0).get("costTier")).isEqualTo("unknown");
    }

    @Test
    void avgLatencyMsAndLastSuccessAtAreNullOrZeroBeforeAnyCall() {
        AiGatewayProperties props = new AiGatewayProperties();
        props.setOrder(List.of("never_called"));
        props.getProviders().put("never_called", providerConfig(null, false, 1000));
        AiGatewayService gateway = gatewayOf(props, new FakeLlmProvider("never_called", true, false));

        Map<String, Object> status = gateway.providerStatuses().get(0);
        assertThat(status.get("avgLatencyMs")).isEqualTo(0L);
        assertThat(status.get("lastSuccessAt")).isNull();
    }

    @Test
    void avgLatencyMsAndLastSuccessAtReflectARealSuccessfulCall() {
        AiGatewayProperties props = new AiGatewayProperties();
        props.setOrder(List.of("sambanova_deepseek_v3_2"));
        props.getProviders().put("sambanova_deepseek_v3_2", providerConfig("low", true, 120000));
        AiGatewayService gateway = gatewayOf(props, new FakeLlmProvider("sambanova_deepseek_v3_2", true, false));

        gateway.chat(List.of(ChatMessage.user("hi")), "sys");

        Map<String, Object> status = gateway.providerStatuses().get(0);
        assertThat((Long) status.get("avgLatencyMs")).isGreaterThanOrEqualTo(0L);
        assertThat(status.get("lastSuccessAt")).isNotNull();
    }

    @Test
    void largeMultiClusterOrderWithSeveralDisabledEntries_routesPastThemAllToTheFirstConfiguredOne() {
        // Mirrors the real NVIDIA+SambaNova combined order: several disabled/unconfigured
        // slots (kimi, glm — analogues) interspersed before a working provider further down.
        AiGatewayProperties props = new AiGatewayProperties();
        List<String> order = List.of(
                "deepseek_flash", "deepseek", "kimi", "glm", "qwen", "gemini", "groq",
                "sambanova_deepseek_v3_2", "sambanova_deepseek_v3_1", "sambanova_gpt_oss_120b",
                "sambanova_llama_3_3_70b", "sambanova_gemma_4_31b", "sambanova_minimax_m2_7",
                "openrouter");
        props.setOrder(order);
        for (String key : order) {
            props.getProviders().put(key, providerConfig(null, false, 1000));
        }

        List<FakeLlmProvider> providers = order.stream()
                .map(key -> new FakeLlmProvider(key, !key.equals("kimi") && !key.equals("glm"), true))
                .toList();
        // Only the very last provider in the chain actually succeeds.
        FakeLlmProvider openrouter = new FakeLlmProvider("openrouter", true, false);
        List<FakeLlmProvider> all = new ArrayList<>(providers.subList(0, providers.size() - 1));
        all.add(openrouter);

        AiGatewayService gateway = gatewayOf(props, all.toArray(new FakeLlmProvider[0]));

        String result = gateway.chat(List.of(ChatMessage.user("hi")), "sys");

        assertThat(result).isEqualTo("openrouter-response");
        // kimi/glm (unconfigured) were never invoked at all.
        FakeLlmProvider kimi = all.stream().filter(p -> p.name().equals("kimi")).findFirst().orElseThrow();
        FakeLlmProvider glm = all.stream().filter(p -> p.name().equals("glm")).findFirst().orElseThrow();
        assertThat(kimi.chatCalls.get()).isZero();
        assertThat(glm.chatCalls.get()).isZero();

        assertThat(gateway.providerStatuses()).hasSize(14);
    }
}
